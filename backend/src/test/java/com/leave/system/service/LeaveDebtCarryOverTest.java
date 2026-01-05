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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class LeaveDebtCarryOverTest {

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private LeaveAccountMapper accountMapper;

    @Autowired
    private LeaveRecordMapper recordMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Test
    public void testDebtCarriesOverToNextYear() {
        // Setup User
        Long userId = 7777L;
        leaveService.deleteAccountsByUserId(userId);
        recordMapper.delete(new QueryWrapper<LeaveRecord>().eq("user_id", userId));

        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername("test_debt_7777");
        user.setRealName("Test Debt");
        // Using 2020 entry date so 2025 quota is full year (assuming we fixed pro-rata
        // or test setup avoids it)
        // But context says we have pro-rata for current year.
        // Let's set entry date to JAN 1 2020.
        // If testing for 2025 (current year), and today is DEC 24, quota is ~4.5.
        // Let's assume quota is 4.5.
        user.setFirstWorkDate(LocalDate.of(2020, 1, 1));
        user.setEntryDate(LocalDate.of(2020, 1, 1));
        user.setStatus("ACTIVE");
        user.setPassword("password");
        userMapper.insert(user);

        // 1. Init 2025 Account -> Quota ~4.5
        leaveService.initYearlyAccount(userId, 2025);

        // 2. Apply for 7 days (Overdraft/Borrow 2.5 days)
        // This generates a "Borrow" record.
        leaveService.applyLeave(userId, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 7));

        // 3. Init 2026 Account
        // This simulates the scheduled task running on Jan 1, 2026
        leaveService.initYearlyAccount(userId, 2026);

        // 4. Check 2026 Account Balance
        LeaveAccount account2026 = accountMapper.selectOne(new QueryWrapper<LeaveAccount>()
                .eq("user_id", userId)
                .eq("year", 2026));

        // Expected:
        // 2026 Quota should be 0.0 (Because 2026 is future year relative to 'now' in
        // test context)
        // Last Year Balance should be NEGATIVE (-2.5) to reflect debt.
        // Total Balance should be -2.5 (0 + (-2.5)).

        assertEquals(new BigDecimal("0.0"), account2026.getActualQuota(), "2026 Quota should be 0.0 (Future year)");

        // This assertion checks if debt (-2.5) is carried over
        assertEquals(new BigDecimal("-2.50"), account2026.getLastYearBalance().setScale(2),
                "Last Year Balance should reflect the debt (-2.5) from 2025");

        assertEquals(new BigDecimal("-2.50"), account2026.getTotalBalance().setScale(2),
                "Total Balance for 2026 should be reduced by debt");
    }
}
