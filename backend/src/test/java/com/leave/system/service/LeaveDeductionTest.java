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
public class LeaveDeductionTest {

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private LeaveAccountMapper accountMapper;

    @Autowired
    private LeaveRecordMapper recordMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Test
    public void testManualAddRecordPriorityDeduction() {
        // Setup User
        Long userId = 888L;
        leaveService.deleteAccountsByUserId(userId);
        recordMapper.delete(new QueryWrapper<LeaveRecord>().eq("user_id", userId));
        userMapper.deleteById(userId);

        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername("test_user_priority");
        user.setRealName("Test Priority");
        user.setFirstWorkDate(LocalDate.of(2020, 1, 1));
        user.setEntryDate(LocalDate.of(2020, 1, 1));
        user.setStatus("ACTIVE");
        user.setPassword("password");
        userMapper.insert(user);

        // Init 2025 Account -> 5.0 quota
        leaveService.initYearlyAccount(userId, 2025);

        // Add Carry Over from 2024: 3.0 days (Expires 2025-12-31)
        LeaveRecord carryOver = new LeaveRecord();
        carryOver.setUserId(userId);
        carryOver.setStartDate(LocalDate.of(2025, 1, 1));
        carryOver.setEndDate(LocalDate.of(2025, 1, 1));
        carryOver.setDays(new BigDecimal("3.0"));
        carryOver.setType("CARRY_OVER");
        carryOver.setExpiryDate(LocalDate.of(2025, 12, 31));
        carryOver.setCreateTime(LocalDateTime.now());
        recordMapper.insert(carryOver);

        // Perform Manual Adjustments via addRecord
        // Scenario: Admin adds a record for 4 days usage.
        // Expectation: 3 days deducted from Carry Over (Exp 2025-12-31), 1 day from
        // 2025 Quota (Exp 2026-12-31)

        LeaveRecord manualUsage = new LeaveRecord();
        manualUsage.setUserId(userId);
        manualUsage.setStartDate(LocalDate.of(2025, 6, 1));
        manualUsage.setEndDate(LocalDate.of(2025, 6, 4));
        // In UI, admin might enter 4.0, backend logic should handle flipping to
        // negative if ANNUAL
        manualUsage.setDays(new BigDecimal("4.0"));
        manualUsage.setType("ANNUAL");
        manualUsage.setRemarks("Admin Manual Deduction");

        // EXECUTE
        leaveService.addRecord(manualUsage);

        // VERIFY
        List<LeaveRecord> records = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                .eq("user_id", userId)
                .eq("type", "ANNUAL")
                .orderByAsc("expiry_date"));

        assertEquals(2, records.size(), "Should verify 2 separate deduction records created");

        LeaveRecord deduction1 = records.get(0);
        assertEquals(new BigDecimal("-3.00"), deduction1.getDays().setScale(2), "First deduction should be 3.0 days");
        assertEquals(LocalDate.of(2025, 12, 31), deduction1.getExpiryDate(),
                "First deduction should use carry-over expiry");

        LeaveRecord deduction2 = records.get(1);
        assertEquals(new BigDecimal("-1.00"), deduction2.getDays().setScale(2), "Second deduction should be 1.0 days");
        assertEquals(LocalDate.of(2026, 12, 31), deduction2.getExpiryDate(),
                "Second deduction should use current year expiry");
    }
}
