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

@SpringBootTest
public class LeaveImplicitCarryOverTest {

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private LeaveAccountMapper accountMapper;

    @Autowired
    private LeaveRecordMapper recordMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Test
    public void testImplicitCarryOverDeduction() {
        // Setup User
        Long userId = 777L;
        leaveService.deleteAccountsByUserId(userId);
        recordMapper.delete(new QueryWrapper<LeaveRecord>().eq("user_id", userId));
        userMapper.deleteById(userId);

        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername("test_implicit");
        user.setRealName("Test Implicit");
        user.setFirstWorkDate(LocalDate.of(2020, 1, 1));
        user.setEntryDate(LocalDate.of(2020, 1, 1));
        user.setStatus("ACTIVE");
        user.setPassword("password");
        userMapper.insert(user);

        // 1. Init 2025 Account -> 5.0 quota, 0 carry over initially
        leaveService.initYearlyAccount(userId, 2025);

        // 2. Simulate Manual Edit of Leave Account (set lastYearBalance to 3.0)
        // CRITICAL: We do NOT insert a CARRY_OVER record.
        LeaveAccount account = accountMapper.selectOne(new QueryWrapper<LeaveAccount>()
                .eq("user_id", userId).eq("year", 2025));
        account.setLastYearBalance(new BigDecimal("3.0")); // Manual edit
        accountMapper.updateById(account);

        // 3. User applies for 4 days leave
        // Expectation:
        // Even though no CARRY_OVER record exists, the system should detect the 3.0
        // days in account
        // as "Implicit Carry Over" expiring 2025-12-31.
        // So: 3.0 days deducted from Implicit (Exp 2025-12-31), 1.0 day from Quota (Exp
        // 2026-12-31)

        leaveService.applyLeave(userId, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 4));

        // VERIFY
        List<LeaveRecord> records = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                .eq("user_id", userId)
                .eq("type", "ANNUAL")
                .orderByAsc("expiry_date"));

        assertEquals(2, records.size(), "Should verify 2 separate deduction records created");

        LeaveRecord deduction1 = records.get(0);
        assertEquals(new BigDecimal("-3.00"), deduction1.getDays().setScale(2), "First deduction should be 3.0 days");
        assertEquals(LocalDate.of(2025, 12, 31), deduction1.getExpiryDate(),
                "First deduction should use implicit carry-over expiry (current year end)");

        LeaveRecord deduction2 = records.get(1);
        assertEquals(new BigDecimal("-1.00"), deduction2.getDays().setScale(2), "Second deduction should be 1.0 days");
        assertEquals(LocalDate.of(2026, 12, 31), deduction2.getExpiryDate(),
                "Second deduction should use current year expiry");
    }
}
