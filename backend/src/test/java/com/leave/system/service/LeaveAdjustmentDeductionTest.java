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
public class LeaveAdjustmentDeductionTest {

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private LeaveAccountMapper accountMapper;

    @Autowired
    private LeaveRecordMapper recordMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Test
    public void testAdjustmentDeductPriority() {
        // Setup User
        Long userId = 666L;
        leaveService.deleteAccountsByUserId(userId);
        recordMapper.delete(new QueryWrapper<LeaveRecord>().eq("user_id", userId));
        userMapper.deleteById(userId);

        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername("test_adjust");
        user.setRealName("Test Adjust");
        user.setFirstWorkDate(LocalDate.of(2020, 1, 1));
        user.setEntryDate(LocalDate.of(2020, 1, 1));
        user.setStatus("ACTIVE");
        user.setPassword("password");
        userMapper.insert(user);

        // 1. Init 2025 Account -> 5.0 quota
        leaveService.initYearlyAccount(userId, 2025);

        // 2. Add Carry Over: 3.0 days (Expires 2025-12-31)
        LeaveRecord carryOver = new LeaveRecord();
        carryOver.setUserId(userId);
        carryOver.setStartDate(LocalDate.of(2025, 1, 1));
        carryOver.setEndDate(LocalDate.of(2025, 1, 1));
        carryOver.setDays(new BigDecimal("3.0"));
        carryOver.setType("CARRY_OVER");
        carryOver.setExpiryDate(LocalDate.of(2025, 12, 31));
        carryOver.setCreateTime(LocalDateTime.now());
        recordMapper.insert(carryOver);

        // 3. User performs "Adjustment Deduct" for 4 days
        // Expectation: 3.0 days deducted from Carry Over, 1.0 day from Quota

        LeaveRecord deduction = new LeaveRecord();
        deduction.setUserId(userId);
        deduction.setStartDate(LocalDate.of(2025, 7, 1));
        deduction.setEndDate(LocalDate.of(2025, 7, 1));
        deduction.setDays(new BigDecimal("4.0")); // Positive value from UI
        deduction.setType("ADJUSTMENT_DEDUCT");
        deduction.setRemarks("Punishment Deduction");

        leaveService.addRecord(deduction);

        // VERIFY
        List<LeaveRecord> records = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                .eq("user_id", userId)
                .in("type", "ANNUAL", "ADJUSTMENT_DEDUCT") // Note: Logic splits into ANNUAL types stored as ANNUAL
                // Wait, let's check what type `deductLeaveDays` stores.
                // It stores as "ANNUAL".
                // Ideally, if it was ADJUSTMENT_DEDUCT coming in, maybe we wanted to keep that
                // type?
                // But `deductLeaveDays` hardcodes "ANNUAL".
                // Let's verify existing behavior first.
                .orderByAsc("expiry_date"));

        // Actually, looking at `deductLeaveDays`, it sets type to "ANNUAL".
        // If user wants to keep "ADJUSTMENT_DEDUCT" type, we might need a small change
        // in `deductLeaveDays` to accept type.
        // But functionally, deduction is deduction.
        // Let's check what we implemented.
        // In `addRecord`: if ADJUSTMENT_DEDUCT -> calls `deductLeaveDays`.
        // In `deductLeaveDays`: `usageRecord.setType("ANNUAL");` -> Hardcoded!

        // This effectively changes the record type from ADJUSTMENT_DEDUCT to ANNUAL in
        // the DB.
        // This might be acceptable (since it consumes quota), but remarks will preserve
        // the context.
        // The test should verify 2 records, checking remarks or consumption.

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
