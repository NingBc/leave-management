package com.leave.system.scheduled;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.LeaveAccountMapper;
import com.leave.system.mapper.LeaveRecordMapper;
import com.leave.system.mapper.SysUserMapper;
import com.leave.system.service.LeaveService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
public class LeaveExpiryCleanupTest {

        @Autowired
        private ScheduledTasks scheduledTasks;

        @Autowired
        private LeaveService leaveService;

        @Autowired
        private LeaveRecordMapper recordMapper;

        @Autowired
        private LeaveAccountMapper accountMapper;

        @Autowired
        private SysUserMapper userMapper;

        @Test
        public void testCleanup() {
                Long userId = System.currentTimeMillis() % 10000000L + 1000000L;
                String username = "test_expiry_" + userId;

                leaveService.deleteAccountsByUserId(userId);
                recordMapper.delete(new QueryWrapper<LeaveRecord>().eq("user_id", userId));
                userMapper.delete(new QueryWrapper<SysUser>().eq("username", username));

                SysUser user = new SysUser();
                user.setId(userId);
                user.setUsername(username);
                user.setRealName("Test Expiry");
                user.setFirstWorkDate(LocalDate.of(2020, 1, 1));
                user.setEntryDate(LocalDate.of(2020, 1, 1));
                user.setStatus("ACTIVE");
                user.setPassword("password");
                userMapper.insert(user);

                LocalDate today = LocalDate.now();
                LocalDate targetExpiryDate = today.minusYears(1).withMonth(12).withDayOfMonth(31);
                int targetYear = targetExpiryDate.getYear();

                // ⚠️ MUST init account because cleanup now iterates over accounts
                // We set actualQuota to 0 to isolate the carry-over logic for this specific
                // test
                leaveService.initYearlyAccount(userId, targetYear);
                LeaveAccount acc = accountMapper.selectOne(new QueryWrapper<LeaveAccount>()
                                .eq("user_id", userId).eq("year", targetYear));
                acc.setActualQuota(BigDecimal.ZERO);
                accountMapper.updateById(acc);

                LeaveRecord carryOver = new LeaveRecord();
                carryOver.setUserId(userId);
                carryOver.setStartDate(LocalDate.of(targetYear, 1, 1));
                carryOver.setEndDate(LocalDate.of(targetYear, 1, 1));
                carryOver.setDays(new BigDecimal("5.0"));
                carryOver.setType("CARRY_OVER");
                carryOver.setExpiryDate(targetExpiryDate);
                carryOver.setCreateTime(LocalDateTime.now());
                recordMapper.insert(carryOver);

                LeaveRecord usage = new LeaveRecord();
                usage.setUserId(userId);
                usage.setStartDate(LocalDate.of(targetYear, 6, 1));
                usage.setEndDate(LocalDate.of(targetYear, 6, 1));
                usage.setDays(new BigDecimal("-2.0"));
                usage.setType("ANNUAL");
                usage.setExpiryDate(targetExpiryDate.plusYears(1));
                usage.setCreateTime(LocalDateTime.now());
                recordMapper.insert(usage);

                scheduledTasks.cleanupExpiredLeaveBalances();

                List<LeaveRecord> expiredRecords = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                                .eq("user_id", userId)
                                .eq("type", "EXPIRED"));

                assertEquals(1, expiredRecords.size());
                assertEquals(new BigDecimal("-5.00"), expiredRecords.get(0).getDays().setScale(2));
        }

        @Test
        public void testCarryOverExcludesExpiredRecords() {
                Long userId = System.currentTimeMillis() % 10000000L + 2000000L;
                String username = "test_carryover_" + userId;

                SysUser user = new SysUser();
                user.setId(userId);
                user.setUsername(username);
                user.setRealName("Test CarryOver");
                user.setFirstWorkDate(LocalDate.of(2020, 1, 1));
                user.setEntryDate(LocalDate.of(2020, 1, 1));
                user.setStatus("ACTIVE");
                user.setPassword("password");
                userMapper.insert(user);

                int currentYear = LocalDate.now().getYear();
                int lastYear = currentYear - 1;

                leaveService.initYearlyAccount(userId, lastYear);

                LeaveRecord expiredRecord = new LeaveRecord();
                expiredRecord.setUserId(userId);
                expiredRecord.setStartDate(LocalDate.of(lastYear, 1, 1));
                expiredRecord.setEndDate(LocalDate.of(lastYear, 1, 1));
                expiredRecord.setDays(new BigDecimal("-3.0"));
                expiredRecord.setType("EXPIRED");
                expiredRecord.setRemarks("Old expiry");
                expiredRecord.setCreateTime(LocalDateTime.now());
                recordMapper.insert(expiredRecord);

                leaveService.initYearlyAccount(userId, currentYear);

                com.leave.system.dto.LeaveAccountDTO account = leaveService.getAccount(userId, currentYear);

                assertEquals(new BigDecimal("5.0").setScale(1), account.getLastYearBalance().setScale(1));
        }

        @Test
        public void testIdempotency() {
                Long userId = System.currentTimeMillis() % 10000000L + 3000000L;
                SysUser user = new SysUser();
                user.setId(userId);
                user.setUsername("test_idempotency_" + userId);
                user.setRealName("Test Idempotency");
                user.setStatus("ACTIVE");
                user.setPassword("password");
                userMapper.insert(user);

                int lastYear = LocalDate.now().getYear() - 1;
                LocalDate targetExpiryDate = LocalDate.of(lastYear, 12, 31);

                // ⚠️ MUST init account because cleanup now iterates over accounts
                leaveService.initYearlyAccount(userId, lastYear);
                LeaveAccount acc = accountMapper.selectOne(new QueryWrapper<LeaveAccount>()
                                .eq("user_id", userId).eq("year", lastYear));
                acc.setActualQuota(BigDecimal.ZERO);
                accountMapper.updateById(acc);

                LeaveRecord carryOver = new LeaveRecord();
                carryOver.setUserId(userId);
                carryOver.setStartDate(LocalDate.of(lastYear, 1, 1));
                carryOver.setEndDate(LocalDate.of(lastYear, 1, 1));
                carryOver.setDays(new BigDecimal("3.00"));
                carryOver.setType("CARRY_OVER");
                carryOver.setExpiryDate(targetExpiryDate);
                carryOver.setCreateTime(LocalDateTime.now());
                recordMapper.insert(carryOver);

                scheduledTasks.cleanupExpiredLeaveBalances(String.valueOf(lastYear));

                List<LeaveRecord> firstPass = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                                .eq("user_id", userId)
                                .eq("type", "EXPIRED")
                                .eq("start_date", targetExpiryDate));
                assertEquals(1, firstPass.size());

                scheduledTasks.cleanupExpiredLeaveBalances(String.valueOf(lastYear));

                List<LeaveRecord> secondPass = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                                .eq("user_id", userId)
                                .eq("type", "EXPIRED")
                                .eq("start_date", targetExpiryDate));
                assertEquals(1, secondPass.size());
        }

        @Test
        public void testDeductionAfterCleanup() {
                Long userId = System.currentTimeMillis() % 10000000L + 4000000L;
                SysUser user = new SysUser();
                user.setId(userId);
                user.setUsername("test_deduct_fix_" + userId);
                user.setRealName("Test Deduct Fix");
                user.setStatus("ACTIVE");
                user.setPassword("password");
                userMapper.insert(user);

                int currentYear = LocalDate.now().getYear();
                int lastYear = currentYear - 1;
                LocalDate targetExpiryDate = LocalDate.of(lastYear, 12, 31);

                LeaveRecord carryOver = new LeaveRecord();
                carryOver.setUserId(userId);
                carryOver.setStartDate(LocalDate.of(lastYear, 1, 1));
                carryOver.setEndDate(LocalDate.of(lastYear, 1, 1));
                carryOver.setDays(new BigDecimal("5.0"));
                carryOver.setType("CARRY_OVER");
                carryOver.setExpiryDate(targetExpiryDate);
                carryOver.setCreateTime(LocalDateTime.now());
                recordMapper.insert(carryOver);

                scheduledTasks.cleanupExpiredLeaveBalances(String.valueOf(lastYear));

                leaveService.initYearlyAccount(userId, currentYear);

                com.leave.system.entity.LeaveAccount account = accountMapper
                                .selectOne(new QueryWrapper<com.leave.system.entity.LeaveAccount>()
                                                .eq("user_id", userId).eq("year", currentYear));
                account.setActualQuota(new BigDecimal("10.0"));
                accountMapper.updateById(account);

                leaveService.applyLeave(userId, LocalDate.now(), LocalDate.now());

                List<LeaveRecord> records = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                                .eq("user_id", userId)
                                .eq("type", "ANNUAL")
                                .orderByDesc("create_time"));

                assertFalse(records.isEmpty());
                LocalDate currentYearExpiry = LocalDate.of(currentYear + 1, 12, 31);
                assertEquals(currentYearExpiry, records.get(0).getExpiryDate());
        }

        @Test
        public void testNoDoubleCountingWithCarryOver() {
                Long userId = System.currentTimeMillis() % 10000000L + 5000000L;
                SysUser user = new SysUser();
                user.setId(userId);
                user.setUsername("test_double_count_" + userId);
                user.setRealName("Test Double Count");
                user.setStatus("ACTIVE");
                user.setPassword("password");
                userMapper.insert(user);

                int lastYear = LocalDate.now().getYear() - 1;
                LocalDate targetExpiryDate = LocalDate.of(lastYear, 12, 31);

                // ⚠️ MUST init account because cleanup now iterates over accounts
                leaveService.initYearlyAccount(userId, lastYear);
                LeaveAccount acc = accountMapper.selectOne(new QueryWrapper<LeaveAccount>()
                                .eq("user_id", userId).eq("year", lastYear));
                acc.setActualQuota(BigDecimal.ZERO);
                accountMapper.updateById(acc);

                LeaveRecord adjustment = new LeaveRecord();
                adjustment.setUserId(userId);
                adjustment.setStartDate(LocalDate.of(lastYear, 6, 1));
                adjustment.setEndDate(LocalDate.of(lastYear, 6, 1));
                adjustment.setDays(new BigDecimal("2.0"));
                adjustment.setType("ADJUSTMENT_ADD");
                adjustment.setExpiryDate(targetExpiryDate);
                adjustment.setCreateTime(LocalDateTime.now().minusHours(2));
                recordMapper.insert(adjustment);

                LeaveRecord usageEarlier = new LeaveRecord();
                usageEarlier.setUserId(userId);
                usageEarlier.setStartDate(LocalDate.of(lastYear, 6, 2));
                usageEarlier.setEndDate(LocalDate.of(lastYear, 6, 2));
                usageEarlier.setDays(new BigDecimal("-1.0"));
                usageEarlier.setType("ANNUAL");
                usageEarlier.setExpiryDate(targetExpiryDate);
                usageEarlier.setCreateTime(LocalDateTime.now().minusHours(1));
                recordMapper.insert(usageEarlier);

                LeaveRecord carryOver = new LeaveRecord();
                carryOver.setUserId(userId);
                carryOver.setStartDate(LocalDate.of(lastYear + 1, 1, 1));
                carryOver.setEndDate(LocalDate.of(lastYear + 1, 1, 1));
                carryOver.setDays(new BigDecimal("6.0"));
                carryOver.setType("CARRY_OVER");
                carryOver.setExpiryDate(targetExpiryDate);
                carryOver.setCreateTime(LocalDateTime.now());
                recordMapper.insert(carryOver);

                LeaveRecord usageLater = new LeaveRecord();
                usageLater.setUserId(userId);
                usageLater.setStartDate(LocalDate.of(lastYear + 1, 2, 1));
                usageLater.setEndDate(LocalDate.of(lastYear + 1, 2, 1));
                usageLater.setDays(new BigDecimal("-0.5"));
                usageLater.setType("ANNUAL");
                usageLater.setExpiryDate(targetExpiryDate);
                usageLater.setCreateTime(LocalDateTime.now().plusMinutes(1));
                recordMapper.insert(usageLater);
                // Cleanup should yield -5.5
                // anchorTime is carryOver (6.0). usageLater (0.5) is after. adjustment (2.0)
                // and usageEarlier (1.0) are before and ignored.
                // 6.0 - 0.5 = 5.5
                scheduledTasks.cleanupExpiredLeaveBalances(String.valueOf(lastYear));

                List<LeaveRecord> expiredRecords = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                                .eq("user_id", userId)
                                .eq("type", "EXPIRED"));

                assertEquals(1, expiredRecords.size());
                assertEquals(new BigDecimal("-5.50"), expiredRecords.get(0).getDays().setScale(2));
        }

        @Test
        @Transactional
        public void testManualDeductionExpiry() {
                Long userId = System.currentTimeMillis() % 10000000L + 2000000L;
                SysUser user = new SysUser();
                user.setId(userId);
                user.setUsername("test_deduct_" + userId);
                user.setRealName("Test Deduct");
                user.setStatus("ACTIVE");
                user.setPassword("password");
                user.setEntryDate(LocalDate.now().minusYears(10));
                user.setFirstWorkDate(LocalDate.now().minusYears(10));
                userMapper.insert(user);

                int currentYear = LocalDate.now().getYear() - 1;
                LocalDate targetExpiryDate = LocalDate.of(currentYear, 12, 31);

                // 1. Manually insert account with stable fields
                LeaveAccount acc = new LeaveAccount();
                acc.setUserId(userId);
                acc.setYear(currentYear);
                acc.setLastYearBalance(new BigDecimal("10.0"));
                acc.setActualQuota(new BigDecimal("5.0"));
                acc.setStandardQuota(new BigDecimal("5.0")); // Needed for refresh
                acc.setDaysEmployed(365);
                accountMapper.insert(acc);

                // 2. Perform a MANUAL deduction via SERVICE
                LeaveRecord manualDeduct = new LeaveRecord();
                manualDeduct.setUserId(userId);
                manualDeduct.setStartDate(LocalDate.of(currentYear, 6, 1));
                manualDeduct.setEndDate(LocalDate.of(currentYear, 6, 1));
                manualDeduct.setDays(new BigDecimal("4.0"));
                manualDeduct.setType("ADJUSTMENT_DEDUCT");
                leaveService.addRecord(manualDeduct);

                // 3. Verify record and run cleanup
                scheduledTasks.cleanupExpiredLeaveBalances(String.valueOf(currentYear));

                List<LeaveRecord> expiredRecords = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                                .eq("user_id", userId)
                                .eq("type", "EXPIRED"));

                assertEquals(1, expiredRecords.size());
                // 10.0 implicit carryover - 4.0 deduction = 6.0 expired.
                assertEquals(new BigDecimal("-6.00"), expiredRecords.get(0).getDays().setScale(2));

                // 4. Verify Total Balance (Quota protected)
                BigDecimal finalBalance = leaveService.getAccount(userId, currentYear).getTotalBalance();
                assertEquals(new BigDecimal("5.00"), finalBalance.setScale(2));
        }

        @Test
        @Transactional
        public void testOverdraftDebtOffsetting() {
                Long userId = System.currentTimeMillis() % 10000000L + 7000000L;
                SysUser user = new SysUser();
                user.setId(userId);
                user.setUsername("test_overdraft_" + userId);
                user.setRealName("Test Overdraft");
                user.setStatus("ACTIVE");
                user.setPassword("password");
                userMapper.insert(user);

                int currentYear = LocalDate.now().getYear();
                LocalDate targetExpiryDate = LocalDate.of(currentYear, 12, 31);

                // 1. Start with 0 balance
                // Initialize account first so we can zero it out
                leaveService.initYearlyAccount(userId, currentYear);
                LeaveAccount account = accountMapper.selectOne(new QueryWrapper<LeaveAccount>()
                                .eq("user_id", userId).eq("year", currentYear));
                account.setStandardQuota(BigDecimal.ZERO);
                account.setActualQuota(BigDecimal.ZERO);
                account.setLastYearBalance(BigDecimal.ZERO);
                accountMapper.updateById(account);

                // Take 3 days leave -> Should create OVERDRAFT since balance is now 0
                leaveService.applyLeave(userId, LocalDate.of(currentYear, 6, 1), LocalDate.of(currentYear, 6, 3));

                // Verify overdraft record
                List<LeaveRecord> odRecords = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                                .eq("user_id", userId)
                                .isNull("expiry_date"));
                assertEquals(1, odRecords.size(), "Should have 1 overdraft record");
                assertEquals(new BigDecimal("-3.00"), odRecords.get(0).getDays().setScale(2));
                assertNull(odRecords.get(0).getExpiryDate(), "Overdraft should have NULL expiry date");

                // 2. Add 10 days credit (expiring end of year)
                LeaveRecord credit = new LeaveRecord();
                credit.setUserId(userId);
                credit.setStartDate(LocalDate.of(currentYear, 1, 1));
                credit.setEndDate(LocalDate.of(currentYear, 1, 1));
                credit.setDays(new BigDecimal("10.0"));
                credit.setType("ADJUSTMENT_ADD");
                credit.setExpiryDate(targetExpiryDate);
                recordMapper.insert(credit);

                // 3. Run cleanup for current year
                scheduledTasks.cleanupExpiredLeaveBalances(String.valueOf(currentYear));

                // 4. Verify results
                // - Should have a Debt-Pool ADDITION (expiry_date IS NULL) to zero out the
                // floating debt
                List<LeaveRecord> debtClearingRecords = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                                .eq("user_id", userId)
                                .eq("type", "ADJUSTMENT_ADD")
                                .isNull("expiry_date")
                                .like("remarks", "冲抵历史欠费"));
                assertEquals(1, debtClearingRecords.size(),
                                "Should have created 1 record to clear the floating debt pool");
                assertEquals(new BigDecimal("3.00"), debtClearingRecords.get(0).getDays().setScale(2));

                // Verify the net floating debt is now 0
                BigDecimal netFloating = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                                .eq("user_id", userId).isNull("expiry_date"))
                                .stream().map(LeaveRecord::getDays).reduce(BigDecimal.ZERO, BigDecimal::add);
                assertEquals(new BigDecimal("0.00"), netFloating.setScale(2), "Net debt pool should be zeroed out");

                // - Should have a Bucket DEDUCTION (expiry_date = targetExpiryDate) to consume
                // the credit
                List<LeaveRecord> bucketConsumptionRecords = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                                .eq("user_id", userId)
                                .eq("type", "ADJUSTMENT_DEDUCT")
                                .eq("expiry_date", targetExpiryDate)
                                .like("remarks", "消耗过期额度"));
                assertEquals(1, bucketConsumptionRecords.size(),
                                "Should have created 1 record to consume bucket credit");
                assertEquals(new BigDecimal("-3.00"), bucketConsumptionRecords.get(0).getDays().setScale(2));

                // - Should have an EXPIRED record for 7 days (10 - 3 consumed = 7 expired)
                List<LeaveRecord> expiredRecords = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                                .eq("user_id", userId)
                                .eq("type", "EXPIRED")
                                .eq("expiry_date", targetExpiryDate));
                assertEquals(1, expiredRecords.size(), "Should have created 1 expired record");
                assertEquals(new BigDecimal("-7.00"), expiredRecords.get(0).getDays().setScale(2));
        }

        @Test
        @Transactional
        public void testNoDoublePenaltyAcrossYears() {
                Long userId = System.currentTimeMillis() % 10000000L + 8000000L;
                SysUser user = new SysUser();
                user.setId(userId);
                user.setUsername("test_penalty_" + userId);
                user.setRealName("Test Penalty");
                user.setStatus("ACTIVE");
                user.setPassword("password");
                userMapper.insert(user);

                int year2025 = 2025;
                int year2026 = 2026;

                // 1. Setup 2025: 5 days quota, 6 days leave (1 day overdraft)
                LeaveAccount acc2025 = leaveService.initYearlyAccount(userId, year2025);
                acc2025.setStandardQuota(new BigDecimal("5.0"));
                acc2025.setActualQuota(new BigDecimal("5.0"));
                acc2025.setLastYearBalance(BigDecimal.ZERO);
                accountMapper.updateById(acc2025);

                // Apply 6 days leave (5 will be bucketed, 1 will be NULL overdraft)
                leaveService.applyLeave(userId, LocalDate.of(year2025, 6, 1), LocalDate.of(year2025, 6, 6));

                // 2. Transition to 2026
                // Initializing 2026 should calculate carry-over
                // 2025 usage (bucketed) was 5.0. Carry-over should be 0 (5.0 quota - 5.0
                // bucketed usage).
                LeaveAccount acc2026 = leaveService.initYearlyAccount(userId, year2026);

                // Verify CarryOver: Should be 0.00
                assertEquals(new BigDecimal("0.00"), acc2026.getLastYearBalance().setScale(2),
                                "Carry over should ignore the 1-day floating debt");

                // Verify Total Balance for 2026: Should be -1.00 (0 carryOver + 0 quota - 1
                // debt)
                BigDecimal totalBalance = leaveService.getAccount(userId, year2026).getTotalBalance();
                assertEquals(new BigDecimal("-1.00"), totalBalance.setScale(2),
                                "Total balance should correctly show the negative debt");

                // 3. Setup 10 days quota in 2026
                acc2026.setActualQuota(new BigDecimal("10.0"));
                accountMapper.updateById(acc2026);

                // Verify Total Balance now: Should be 9.00 (10 quota - 1 debt)
                totalBalance = leaveService.getAccount(userId, year2026).getTotalBalance();
                assertEquals(new BigDecimal("9.00"), totalBalance.setScale(2), "Total balance should be 9.00");

                // 4. Run 2026 Cleanup
                scheduledTasks.cleanupExpiredLeaveBalances(String.valueOf(year2026));

                // 5. Verify Cleanup Results
                // - Debt should be offset (1.0)
                BigDecimal netFloating = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                                .eq("user_id", userId).isNull("expiry_date"))
                                .stream().map(LeaveRecord::getDays).reduce(BigDecimal.ZERO, BigDecimal::add);
                assertEquals(new BigDecimal("0.00"), netFloating.setScale(2), "Debt should be cleared");

                // - No "EXPIRED" records should exist for 2026-12-31
                // because the balance (9.0) belongs to the 2026 quota which expires in 2027.
                List<LeaveRecord> expiredRecords = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                                .eq("user_id", userId)
                                .eq("type", "EXPIRED")
                                .eq("expiry_date", LocalDate.of(year2026, 12, 31)));
                assertEquals(0, expiredRecords.size(), "Quota should not expire until next year");

                // - Total balance should still be 9.00
                totalBalance = leaveService.getAccount(userId, year2026).getTotalBalance();
                assertEquals(new BigDecimal("9.00"), totalBalance.setScale(2),
                                "9.00 should still be available in the 2026 quota");

                // - There should be an ADJUSTMENT_DEDUCT for the offset that expires in 2027
                List<LeaveRecord> deductRecords = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                                .eq("user_id", userId)
                                .eq("type", "ADJUSTMENT_DEDUCT")
                                .eq("expiry_date", LocalDate.of(year2026 + 1, 12, 31)));
                assertEquals(1, deductRecords.size(), "Should have consumed 1.0 from the 2027-expiry bucket");
                assertEquals(new BigDecimal("-1.00"), deductRecords.get(0).getDays().setScale(2));
        }
}
