package com.leave.system.it;

import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.LeaveAccountMapper;
import com.leave.system.mapper.LeaveRecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 SQL 验证。
 *
 * <p>
 * 内存假库是按 XML 的<b>语义</b>手写的, 假库对不代表 SQL 对 —— 这次改过的几条
 * (selectLedgerRecords / selectFloatingRecordsForCleanup / sumAnnualLeaveUsage /
 * deleteAccountsAfterYear) 必须在真实 MySQL 上跑一遍, 同时也验证假库的语义没写偏。
 */
class LeaveSqlIT extends IntegrationTestBase {

    @Autowired
    LeaveRecordMapper recordMapper;

    @Autowired
    LeaveAccountMapper accountMapper;

    private SysUser user;

    private SysUser user() {
        if (user == null) {
            user = createTestUser("sql", 2L, LocalDate.of(2015, 1, 1), LocalDate.of(2018, 3, 1));
        }
        return user;
    }

    private LeaveRecord insert(String type, String days, LocalDate expiry, LocalDate start, LocalDate end) {
        LeaveRecord r = new LeaveRecord();
        r.setUserId(user().getId());
        r.setType(type);
        r.setDays(new BigDecimal(days));
        r.setExpiryDate(expiry);
        r.setStartDate(start);
        r.setEndDate(end);
        r.setRemarks("it");
        r.setCreateTime(LocalDateTime.now());
        r.setDeleted(0);
        recordMapper.insertRecord(r);
        assertNotNull(r.getId(), "insertRecord 应当回填自增主键");
        return r;
    }

    @Test
    @DisplayName("selectLedgerRecords: 只取 from 之后的非 CARRY_OVER 流水")
    void ledgerRecordsScope() {
        insert("ANNUAL", "-1.0", LocalDate.of(2025, 12, 31), LocalDate.of(2023, 5, 1), LocalDate.of(2023, 5, 1));
        insert("CARRY_OVER", "3.0", LocalDate.of(2024, 12, 31), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1));
        insert("ANNUAL", "-2.0", LocalDate.of(2025, 12, 31), LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 1));
        insert("ANNUAL", "-0.5", null, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 1));

        List<LeaveRecord> ledger = recordMapper.selectLedgerRecords(user().getId(), LocalDate.of(2024, 1, 1));

        assertEquals(2, ledger.size(), "应排除 2023 年的流水和 CARRY_OVER");
        assertTrue(ledger.stream().noneMatch(r -> "CARRY_OVER".equals(r.getType())));
        assertTrue(ledger.stream().allMatch(r -> !r.getStartDate().isBefore(LocalDate.of(2024, 1, 1))));
        // ORDER BY start_date ASC, id ASC
        assertEquals(LocalDate.of(2024, 2, 1), ledger.get(0).getStartDate());
        assertEquals(LocalDate.of(2024, 6, 1), ledger.get(1).getStartDate());
    }

    @Test
    @DisplayName("selectLedgerRecords: 逻辑删除的流水不参与账本")
    void ledgerRecordsSkipDeleted() {
        LeaveRecord r = insert("ANNUAL", "-2.0", null, LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 1));
        assertEquals(1, recordMapper.selectLedgerRecords(user().getId(), LocalDate.of(2024, 1, 1)).size());

        LeaveRecord del = new LeaveRecord();
        del.setId(r.getId());
        del.setDeleted(1);
        recordMapper.updateRecord(del);

        assertTrue(recordMapper.selectLedgerRecords(user().getId(), LocalDate.of(2024, 1, 1)).isEmpty());
    }

    @Test
    @DisplayName("selectFloatingRecordsForCleanup: 按年度下界过滤, 这是跨年不重复计债的关键")
    void floatingRecordsAreYearScoped() {
        insert("ANNUAL", "-1.5", null, LocalDate.of(2023, 2, 1), LocalDate.of(2023, 2, 1));
        insert("ANNUAL", "-2.0", null, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 1));
        insert("CARRY_OVER", "-1.0", null, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1));

        List<LeaveRecord> f2024 = recordMapper.selectFloatingRecordsForCleanup(
                user().getId(), LocalDate.of(2024, 1, 1));
        assertEquals(1, f2024.size(), "只应看到 2024 年产生的透支, CARRY_OVER 排除在外");
        assertEquals(0, new BigDecimal("-2.0").compareTo(f2024.get(0).getDays()));

        List<LeaveRecord> all = recordMapper.selectFloatingRecordsForCleanup(
                user().getId(), LocalDate.of(2023, 1, 1));
        assertEquals(2, all.size());
    }

    @Test
    @DisplayName("sumAnnualLeaveUsage: 跨天记录按自然日均摊, 覆盖区间内每一天")
    void multiDayLeaveIsSpreadAcrossDays() {
        // 3/1~3/5 共 5 天, 合计 -5.0 → 每天 1.0
        insert("ANNUAL", "-5.0", LocalDate.of(2025, 12, 31), LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 5));

        for (int day = 1; day <= 5; day++) {
            BigDecimal used = recordMapper.sumAnnualLeaveUsage(user().getId(), LocalDate.of(2024, 3, day));
            assertEquals(0, new BigDecimal("-1.0").compareTo(used),
                    "3 月 " + day + " 日应当算作已用 1 天 —— 旧写法只匹配 start_date, "
                            + "会让钉钉同步把 3/2~3/5 重复扣一遍");
        }
        assertEquals(0, BigDecimal.ZERO.compareTo(
                recordMapper.sumAnnualLeaveUsage(user().getId(), LocalDate.of(2024, 3, 6))));
    }

    @Test
    @DisplayName("deleteAccountsAfterYear: 只软删指定年度之后的账户")
    void deleteAccountsAfterYearKeepsHistory() {
        for (int year : new int[] { 2022, 2023, 2024 }) {
            LeaveAccount a = new LeaveAccount();
            a.setUserId(user().getId());
            a.setYear(year);
            a.setStandardQuota(new BigDecimal("5.0"));
            a.setActualQuota(new BigDecimal("5.0"));
            a.setLastYearBalance(BigDecimal.ZERO);
            a.setDaysEmployed(365);
            a.setSocialSeniority(5);
            a.setDeleted(0);
            accountMapper.insertAccount(a);
        }

        accountMapper.deleteAccountsAfterYear(user().getId(), 2023);

        assertNotNull(accountMapper.selectAccountByUserIdAndYear(user().getId(), 2022), "历史年度保留");
        assertNotNull(accountMapper.selectAccountByUserIdAndYear(user().getId(), 2023), "离职当年保留");
        assertNull(accountMapper.selectAccountByUserIdAndYear(user().getId(), 2024), "之后的年度应被软删");
    }

    @Test
    @DisplayName("updateAccount 的动态 SET: 只更新非空字段")
    void updateAccountOnlyTouchesNonNullFields() {
        LeaveAccount a = new LeaveAccount();
        a.setUserId(user().getId());
        a.setYear(2024);
        a.setStandardQuota(new BigDecimal("10.0"));
        a.setActualQuota(new BigDecimal("7.5"));
        a.setLastYearBalance(new BigDecimal("2.0"));
        a.setDaysEmployed(200);
        a.setSocialSeniority(9);
        a.setDeleted(0);
        accountMapper.insertAccount(a);

        LeaveAccount patch = new LeaveAccount();
        patch.setId(a.getId());
        patch.setActualQuota(new BigDecimal("9.0"));
        accountMapper.updateAccount(patch);

        LeaveAccount after = accountMapper.selectAccountByUserIdAndYear(user().getId(), 2024);
        assertEquals(0, new BigDecimal("9.0").compareTo(after.getActualQuota()));
        assertEquals(0, new BigDecimal("2.0").compareTo(after.getLastYearBalance()), "未传的字段不能被清零");
        assertEquals(200, after.getDaysEmployed());
    }

    @Test
    @DisplayName("V2 之后 leave_account 已无 current_year_used / total_balance, 插入与查询照常")
    void schemaAfterMigration() {
        Integer legacyColumns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leave_account' "
                        + "AND COLUMN_NAME IN ('current_year_used','total_balance')",
                Integer.class);
        assertEquals(0, legacyColumns, "V2 迁移应当已删除这两个派生列");

        LeaveAccount a = new LeaveAccount();
        a.setUserId(user().getId());
        a.setYear(2024);
        a.setStandardQuota(new BigDecimal("5.0"));
        a.setActualQuota(new BigDecimal("5.0"));
        a.setLastYearBalance(BigDecimal.ZERO);
        a.setDaysEmployed(365);
        a.setSocialSeniority(5);
        a.setDeleted(0);
        assertEquals(1, accountMapper.insertAccount(a));
        assertNotNull(accountMapper.selectAccountByUserIdAndYear(user().getId(), 2024));
    }
}
