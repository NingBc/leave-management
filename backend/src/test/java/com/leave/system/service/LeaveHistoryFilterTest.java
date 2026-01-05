package com.leave.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.LeaveRecordMapper;
import com.leave.system.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
public class LeaveHistoryFilterTest {

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private LeaveRecordMapper recordMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Test
    public void testHistoryYearFilter() {
        Long userId = 998877L;
        String username = "test_filter_user";

        // Clean state
        leaveService.deleteAccountsByUserId(userId);
        recordMapper.delete(new QueryWrapper<LeaveRecord>().eq("user_id", userId));
        userMapper.delete(new QueryWrapper<SysUser>().eq("username", username));
        userMapper.deleteById(userId);

        // Setup user
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername(username);
        user.setPassword("password");
        user.setEntryDate(LocalDate.of(2020, 1, 1));
        userMapper.insert(user);

        // Setup records across different years
        createRecord(userId, 2023, 5, "ANNUAL");
        createRecord(userId, 2024, 3, "ANNUAL");
        createRecord(userId, 2024, 2, "ADJUSTMENT_ADD");
        createRecord(userId, 2025, 1, "ANNUAL");

        // Verify "All" history
        List<LeaveRecord> allHistory = leaveService.getHistory(userId, null);
        assertEquals(4, allHistory.size(), "Should return all 4 records");

        // Verify 2024 filter
        List<LeaveRecord> history2024 = leaveService.getHistory(userId, 2024);
        assertEquals(2, history2024.size(), "Should return exactly 2 records for 2024");

        // Verify 2023 filter
        List<LeaveRecord> history2023 = leaveService.getHistory(userId, 2023);
        assertEquals(1, history2023.size(), "Should return exactly 1 record for 2023");

        // Verify 2026 filter (no records)
        List<LeaveRecord> history2026 = leaveService.getHistory(userId, 2026);
        assertEquals(0, history2026.size(), "Should return 0 records for 2026");
    }

    private void createRecord(Long userId, int year, int days, String type) {
        LeaveRecord record = new LeaveRecord();
        record.setUserId(userId);
        record.setStartDate(LocalDate.of(year, 6, 1));
        record.setEndDate(LocalDate.of(year, 6, 1));
        record.setDays(new BigDecimal(days));
        record.setType(type);
        record.setCreateTime(LocalDateTime.now());
        recordMapper.insert(record);
    }
}
