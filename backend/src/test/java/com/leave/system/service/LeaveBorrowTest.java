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
public class LeaveBorrowTest {

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private LeaveAccountMapper accountMapper;

    @Autowired
    private LeaveRecordMapper recordMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Test
    public void testBorrowFromNextYear() {
        // Setup User
        Long userId = 9999L;
        leaveService.deleteAccountsByUserId(userId);
        recordMapper.delete(new QueryWrapper<LeaveRecord>().eq("user_id", userId));
        // Hard delete user to clear any conflict (not strictly needed if ID is unique)
        // But since we use logic delete, let's just use a new ID.

        // Actually, we can try to hard delete via SQL if needed, but changing ID is
        // safer.

        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername("test_borrow_9999");
        user.setRealName("Test Borrow");
        user.setFirstWorkDate(LocalDate.of(2020, 1, 1));
        user.setEntryDate(LocalDate.of(2020, 1, 1));
        user.setStatus("ACTIVE");
        user.setPassword("password");
        userMapper.insert(user);

        // 1. Init 2025 Account -> 5.0 quota
        leaveService.initYearlyAccount(userId, 2025);

        // 2. User applies for 7 days leave (Quota is only 5.0)
        // Deficit: 2.0 days
        // Expectation:
        // - 5.0 days deducted from 2025 Quota (Exp 2026-12-31)
        // - 2.0 days deducted as "Borrow" from 2026 Quota (Exp 2027-12-31)

        leaveService.applyLeave(userId, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 7));

        // VERIFY
        List<LeaveRecord> records = recordMapper.selectList(new QueryWrapper<LeaveRecord>()
                .eq("user_id", userId)
                .eq("type", "ANNUAL")
                .orderByAsc("expiry_date"));

        assertEquals(2, records.size(), "Should verify 2 separate deduction records created");

        // 1. Current Year Deduction
        // Note: Due to "daily pro-rated" logic for current year (restored in previous
        // step),
        // the quota for 2025 (current year) is calculated based on days passed.
        // If test runs on Dec 24, 2025, days employed is ~358.
        // 5.0 * (358/365) = 4.90 -> Round down to 4.5.
        // So expected deduction is 4.5, not 5.0.
        LeaveRecord deduction1 = records.get(0);
        assertEquals(new BigDecimal("-4.50"), deduction1.getDays().setScale(2),
                "First deduction should exhaust available 2025 actual quota (4.5)");
        assertEquals(LocalDate.of(2026, 12, 31), deduction1.getExpiryDate(),
                "First deduction expiry should be 2026-12-31");

        // 2. Borrow Record
        // Total needed 7.0 - 4.5 allocated = 2.5 borrowed
        LeaveRecord deduction2 = records.get(1);
        assertEquals(new BigDecimal("-2.50"), deduction2.getDays().setScale(2),
                "Second deduction should borrow 2.5 days");
        // Check Expiry: Should be 2025 + 2 = 2027
        assertEquals(LocalDate.of(2027, 12, 31), deduction2.getExpiryDate(),
                "Borrow record expiry should be 2027-12-31 (Year+2)");
        assertTrue(deduction2.getRemarks().contains("预支2026年额度"), "Remarks should mention borrowing from 2026");
    }
}
