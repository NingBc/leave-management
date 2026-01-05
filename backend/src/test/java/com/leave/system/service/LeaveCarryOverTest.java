package com.leave.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.LeaveAccountMapper;
import com.leave.system.mapper.LeaveRecordMapper;
import com.leave.system.mapper.SysUserMapper;
import com.leave.system.service.impl.LeaveServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class LeaveCarryOverTest {

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private LeaveAccountMapper accountMapper;

    @Autowired
    private LeaveRecordMapper recordMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Test
    public void testCarryOverCalculationDebug() {
        // 1. Setup Test User (Seniority 5 years -> 5 days quota)
        Long userId = 999L;
        // Clean up previous test data
        leaveService.deleteAccountsByUserId(userId);
        recordMapper.delete(new QueryWrapper<LeaveRecord>().eq("user_id", userId));
        userMapper.deleteById(userId);

        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername("test_admin");
        user.setRealName("Test Admin");
        user.setFirstWorkDate(LocalDate.of(2020, 1, 1)); // 5 years seniority in 2025
        user.setEntryDate(LocalDate.of(2020, 1, 1));
        user.setStatus("ACTIVE");
        user.setPassword("password");
        userMapper.insert(user);

        // 2. Initialize 2025 Account
        // 2025 Quota should be 5.0 days
        leaveService.initYearlyAccount(userId, 2025);
        LeaveAccount account2025 = accountMapper.selectOne(new QueryWrapper<LeaveAccount>()
                .eq("user_id", userId).eq("year", 2025));
        assertNotNull(account2025, "2025 Account should exist");
        assertEquals(new BigDecimal("5.0"), account2025.getActualQuota(), "2025 Actual Quota should be 5.0");

        // 3. Simulate 2024 Carry Over (+3 days)
        // Manually insert a record representing 3 days carried over from 2024
        // Expiry date must be 2025-12-31
        LeaveRecord carryOverRecord = new LeaveRecord();
        carryOverRecord.setUserId(userId);
        carryOverRecord.setStartDate(LocalDate.of(2025, 1, 1));
        carryOverRecord.setEndDate(LocalDate.of(2025, 1, 1));
        carryOverRecord.setDays(new BigDecimal("3.0"));
        carryOverRecord.setType("CARRY_OVER");
        carryOverRecord.setExpiryDate(LocalDate.of(2025, 12, 31));
        carryOverRecord.setCreateTime(LocalDateTime.now());
        recordMapper.insert(carryOverRecord);

        // Update 2025 Account to reflect this carry over
        account2025.setLastYearBalance(new BigDecimal("3.0"));
        accountMapper.updateById(account2025);

        // 4. Simulate Usage in 2025 (-4 days)
        // User applies for 4 days leave.
        // Logic: Should deduct 3 days from Carry Over (Exp 2025-12-31) and 1 day from
        // 2025 Quota (Exp 2026-12-31)

        // Record 1: Deduct 3 days from Carry Over
        LeaveRecord usage1 = new LeaveRecord();
        usage1.setUserId(userId);
        usage1.setStartDate(LocalDate.of(2025, 6, 1));
        usage1.setDays(new BigDecimal("-3.0"));
        usage1.setType("ANNUAL");
        usage1.setExpiryDate(LocalDate.of(2025, 12, 31)); // Important: Matches carry over expiry
        usage1.setEndDate(LocalDate.of(2025, 6, 3));
        usage1.setCreateTime(LocalDateTime.now());
        recordMapper.insert(usage1);

        // Record 2: Deduct 1 day from 2025 Quota
        LeaveRecord usage2 = new LeaveRecord();
        usage2.setUserId(userId);
        usage2.setStartDate(LocalDate.of(2025, 6, 4));
        usage2.setDays(new BigDecimal("-1.0"));
        usage2.setType("ANNUAL");
        usage2.setExpiryDate(LocalDate.of(2026, 12, 31)); // Important: Matches 2025 quota expiry
        usage2.setEndDate(LocalDate.of(2025, 6, 4));
        usage2.setCreateTime(LocalDateTime.now());
        recordMapper.insert(usage2);

        // 5. Initialize 2026 Account (Trigger Carry Over Calculation)
        // Expected Carry Over logic:
        // 2025 Quota (5.0) + Net Change
        // Net Change should IGNORE records expiring before 2026-01-01
        // Ignored: Carry Over (+3), Usage 1 (-3)
        // Included: Usage 2 (-1)
        // Expected: 5.0 + (-1.0) = 4.0
        leaveService.initYearlyAccount(userId, 2026);

        LeaveAccount account2026 = accountMapper.selectOne(new QueryWrapper<LeaveAccount>()
                .eq("user_id", userId).eq("year", 2026));

        System.out.println("2026 Carry Over (Last Year Balance): " + account2026.getLastYearBalance());

        assertEquals(new BigDecimal("4.0"), account2026.getLastYearBalance(), "2026 Carry Over should be 4.0");
    }
}
