package com.leave.system.scheduled;

import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.LeaveAccountMapper;
import com.leave.system.mapper.LeaveRecordMapper;
import com.leave.system.service.LeaveService;
import com.leave.system.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Scheduled tasks for leave management system
 */
@Component
public class ScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTasks.class);

    private final LeaveRecordMapper recordMapper;
    private final LeaveAccountMapper accountMapper;
    private final LeaveService leaveService;
    private final UserService userService;

    public ScheduledTasks(LeaveRecordMapper recordMapper,
            LeaveAccountMapper accountMapper,
            LeaveService leaveService,
            UserService userService) {
        this.recordMapper = recordMapper;
        this.accountMapper = accountMapper;
        this.leaveService = leaveService;
        this.userService = userService;
    }

    /**
     * Cleanup expired leave balances
     * Runs every year on January 1 at 3:00 AM
     * Defaults to cleaning up balances that expired on Dec 31 of last year
     */
    @Transactional(rollbackFor = Exception.class)
    public void cleanupExpiredLeaveBalances() {
        int lastYear = LocalDate.now().getYear() - 1;
        performCleanupForYear(lastYear);
    }

    /**
     * Manual trigger version of expiry cleanup
     * 
     * @param year The year to clean balances for (e.g. "2024")
     */
    @Transactional(rollbackFor = Exception.class)
    public void cleanupExpiredLeaveBalances(String year) {
        try {
            int targetYear = Integer.parseInt(year);
            performCleanupForYear(targetYear);
        } catch (NumberFormatException e) {
            log.error("❌ Invalid year parameter for manual cleanup: {}", year);
        }
    }

    /**
     * Core logic for leave expiry cleanup
     * 
     * @param cleanupYear The year whose Dec 31 expiry balances should be cleaned
     */
    private void performCleanupForYear(int cleanupYear) {
        LocalDate targetExpiryDate = LocalDate.of(cleanupYear, 12, 31);

        log.info("🔄 Starting leave expiry cleanup for year: {} (Target Expiry: {})", cleanupYear, targetExpiryDate);

        // 1. Get all users who have an account for this year
        List<LeaveAccount> yearAccounts = accountMapper.selectAccountsByYear(cleanupYear);

        if (yearAccounts.isEmpty()) {
            log.info("No accounts found for year {}", cleanupYear);
            return;
        }

        int totalUsersAffected = 0;
        BigDecimal totalDaysExpired = BigDecimal.ZERO;

        for (LeaveAccount account : yearAccounts) {
            Long userId = account.getUserId();

            // Find records for this user that expire on the target date (Bucket credits)
            // Find records for this user that expire on the target date (Bucket credits)
            List<LeaveRecord> userRecords = recordMapper.selectExpiringRecords(userId, targetExpiryDate);

            Optional<LeaveRecord> latestCarryOver = userRecords.stream()
                    .filter(r -> "CARRY_OVER".equals(r.getType()))
                    .max(Comparator.comparing(LeaveRecord::getCreateTime));

            final BigDecimal expiringBalance;
            final LocalDateTime anchorTime;

            if (latestCarryOver.isPresent()) {
                LeaveRecord carryOver = latestCarryOver.get();
                final LocalDateTime snapshotTime = carryOver.getCreateTime();
                anchorTime = snapshotTime;
                BigDecimal baseBalance = carryOver.getDays();

                BigDecimal extraCredits = userRecords.stream()
                        .filter(r -> !"CARRY_OVER".equals(r.getType()))
                        .filter(r -> r.getCreateTime().isAfter(snapshotTime))
                        .map(LeaveRecord::getDays)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                expiringBalance = baseBalance.add(extraCredits);
                log.debug("Using CARRY_OVER snapshot as base for user {}: {} + {} extra = {}",
                        userId, baseBalance, extraCredits, expiringBalance);
            } else {
                anchorTime = null;
                expiringBalance = userRecords.stream()
                        .map(LeaveRecord::getDays)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                log.debug("No CARRY_OVER found. Summing all credits for user {}: {}", userId, expiringBalance);
            }

            BigDecimal recordedCarryOverSum = userRecords.stream()
                    .filter(r -> "CARRY_OVER".equals(r.getType()))
                    .map(LeaveRecord::getDays)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal accountLastYearBalance = account.getLastYearBalance() != null ? account.getLastYearBalance()
                    : BigDecimal.ZERO;

            BigDecimal finalExpiringBalance = expiringBalance;
            if (accountLastYearBalance.compareTo(recordedCarryOverSum) > 0) {
                BigDecimal implicitDiff = accountLastYearBalance.subtract(recordedCarryOverSum);
                finalExpiringBalance = expiringBalance.add(implicitDiff);
                log.info("ℹ️ Adding implicit carry-over diff for user {}: {}", userId, implicitDiff);
            }

            BigDecimal protectionBalance = BigDecimal.ZERO;
            if (targetExpiryDate.getYear() == account.getYear() && account.getActualQuota() != null) {
                protectionBalance = account.getActualQuota();
            }

            List<LeaveRecord> usageRecords = recordMapper.selectUsageRecordsForExpiryCleanup(userId, targetExpiryDate,
                    anchorTime);
            BigDecimal totalUsed = usageRecords.stream()
                    .map(LeaveRecord::getDays)
                    .map(BigDecimal::abs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal remainingExpiring = finalExpiringBalance.subtract(totalUsed);

            List<LeaveRecord> alreadyExpiredRecords = recordMapper.selectExpiredRecordsByDate(userId, targetExpiryDate);

            BigDecimal alreadyExpiredAmount = alreadyExpiredRecords.stream()
                    .map(LeaveRecord::getDays)
                    .map(BigDecimal::abs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            remainingExpiring = remainingExpiring.subtract(alreadyExpiredAmount);

            List<LeaveRecord> floatingRecords = recordMapper.selectFloatingRecordsForCleanup(userId);

            BigDecimal currentNetDebt = floatingRecords.stream()
                    .map(LeaveRecord::getDays)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (currentNetDebt.compareTo(BigDecimal.ZERO) < 0) {
                BigDecimal debtToSettle = currentNetDebt.abs();

                if (remainingExpiring.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal offsetFromExpiring = remainingExpiring.min(debtToSettle);
                    log.info("💰 Debt Settlement (Tier 1): Settle {} days using EXPIRING bucket {} for user {}",
                            offsetFromExpiring, targetExpiryDate, userId);

                    LeaveRecord bucketDeduct = new LeaveRecord();
                    bucketDeduct.setUserId(userId);
                    bucketDeduct.setStartDate(targetExpiryDate);
                    bucketDeduct.setEndDate(targetExpiryDate);
                    bucketDeduct.setDays(offsetFromExpiring.negate());
                    bucketDeduct.setType("ADJUSTMENT_DEDUCT");
                    bucketDeduct.setExpiryDate(targetExpiryDate);
                    bucketDeduct.setRemarks("系统自动清理透支: 消耗过期额度 (" + targetExpiryDate + ")");
                    recordMapper.insertRecord(bucketDeduct);

                    LeaveRecord debtOffset = new LeaveRecord();
                    debtOffset.setUserId(userId);
                    debtOffset.setStartDate(targetExpiryDate);
                    debtOffset.setEndDate(targetExpiryDate);
                    debtOffset.setDays(offsetFromExpiring);
                    debtOffset.setType("ADJUSTMENT_ADD");
                    debtOffset.setRemarks("系统自动清理透支: 冲抵历史欠费 (来源: " + targetExpiryDate + ")");
                    recordMapper.insertRecord(debtOffset);

                    debtToSettle = debtToSettle.subtract(offsetFromExpiring);
                    remainingExpiring = remainingExpiring.subtract(offsetFromExpiring);
                }

                if (debtToSettle.compareTo(BigDecimal.ZERO) > 0 && protectionBalance.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal offsetFromProtection = protectionBalance.min(debtToSettle);
                    LocalDate nextYearExpiry = LocalDate.of(targetExpiryDate.getYear() + 1, 12, 31);

                    log.info(
                            "💰 Debt Settlement (Tier 2): Settle {} days using PROTECTION bucket (expires {}) for user {}",
                            offsetFromProtection, nextYearExpiry, userId);

                    LeaveRecord bucketDeduct = new LeaveRecord();
                    bucketDeduct.setUserId(userId);
                    bucketDeduct.setStartDate(targetExpiryDate);
                    bucketDeduct.setEndDate(targetExpiryDate);
                    bucketDeduct.setDays(offsetFromProtection.negate());
                    bucketDeduct.setType("ADJUSTMENT_DEDUCT");
                    bucketDeduct.setExpiryDate(nextYearExpiry);
                    bucketDeduct.setRemarks("系统自动清理透支: 消耗当年配额 (过期: " + nextYearExpiry + ")");
                    recordMapper.insertRecord(bucketDeduct);

                    LeaveRecord debtOffset = new LeaveRecord();
                    debtOffset.setUserId(userId);
                    debtOffset.setStartDate(targetExpiryDate);
                    debtOffset.setEndDate(targetExpiryDate);
                    debtOffset.setDays(offsetFromProtection);
                    debtOffset.setType("ADJUSTMENT_ADD");
                    debtOffset.setRemarks("系统自动清理透支: 冲抵历史欠费 (来源: 当年配额)");
                    recordMapper.insertRecord(debtOffset);

                    debtToSettle = debtToSettle.subtract(offsetFromProtection);
                }
            }

            if (remainingExpiring.compareTo(BigDecimal.ZERO) > 0) {
                LeaveRecord expiredRecord = new LeaveRecord();
                expiredRecord.setUserId(userId);
                expiredRecord.setStartDate(targetExpiryDate);
                expiredRecord.setEndDate(targetExpiryDate);
                expiredRecord.setDays(remainingExpiring.negate());
                expiredRecord.setType("EXPIRED");
                expiredRecord.setExpiryDate(targetExpiryDate);
                expiredRecord.setRemarks("年假已过期自动清理 (到期日期: " + targetExpiryDate + ")");
                recordMapper.insertRecord(expiredRecord);

                totalDaysExpired = totalDaysExpired.add(remainingExpiring);
                totalUsersAffected++;
                log.info("⏱️  Expired {} days for user {} (target expiry date: {})",
                        remainingExpiring, userId, targetExpiryDate);
            }
        }

        log.info("✅ Expiry cleanup for year {} completed: {} users affected, {} total days expired",
                cleanupYear, totalUsersAffected, totalDaysExpired);
    }

    public void initAllAccounts() {
        initAllAccounts(null);
    }

    public void initAllAccounts(String year) {
        Integer targetYear;
        if (year == null || year.trim().isEmpty() || "DEFAULT".equalsIgnoreCase(year)) {
            targetYear = LocalDate.now().getYear();
            log.info("ℹ️ No year specified, defaulting to current year: {}", targetYear);
        } else {
            try {
                targetYear = Integer.parseInt(year);
            } catch (NumberFormatException e) {
                log.error("Invalid year parameter: {}", year);
                return;
            }
        }

        log.info("🔄 Starting batch account initialization for year: {}", targetYear);

        int successCount = 0;
        int failCount = 0;

        try {
            List<SysUser> users = userService.getAllUsers();

            for (SysUser user : users) {
                try {
                    if ("RESIGNED".equals(user.getStatus())) {
                        continue;
                    }

                    leaveService.initYearlyAccount(user.getId(), targetYear);
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    log.error("Failed to init account for user {}: {}", user.getId(), e.getMessage());
                }
            }

            log.info("✅ Batch initialization completed: {} success, {} failed out of {} total users",
                    successCount, failCount, users.size());
        } catch (Exception e) {
            log.error("❌ Batch initialization failed", e);
        }
    }

}
