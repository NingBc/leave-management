package com.leave.system.it;

import com.leave.system.scheduled.ScheduledTasks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 年终结算彩排 —— 拿从生产复制过来的真实数据跑一遍完整结算。
 *
 * <p>
 * 前提: 测试库里已经放好生产快照, 且账户年度与流水日期整体前移了一年 (2026 → 2025),
 * 这样目标年度完全落在过去, {@code quotaReferenceDate} 恒为 12/31, 额度会按整年算定 ——
 * 等价于真实跨年那一刻的行为。准备脚本见 {@code REHEARSAL_SETUP}。
 *
 * <p>
 * 这个用例<b>会改动测试库里那份生产副本</b>, 所以默认跳过, 需要时显式指定:
 * <pre>
 *   mvn -o test -Dtest=YearEndRehearsalIT -Dleave.rehearsal=true
 * </pre>
 */
class YearEndRehearsalIT extends IntegrationTestBase {

    static final String REHEARSAL_SETUP = """
            UPDATE leave_account SET year = 2025 WHERE year = 2026;
            UPDATE leave_record SET start_date = DATE_SUB(start_date, INTERVAL 1 YEAR),
                                    end_date = DATE_SUB(end_date, INTERVAL 1 YEAR),
                                    expiry_date = DATE_SUB(expiry_date, INTERVAL 1 YEAR);
            """;

    private static final int CLEANUP_YEAR = 2025;

    @Autowired
    ScheduledTasks tasks;

    @Test
    @DisplayName("对真实数据跑 2025→2026 结算: 守恒、幂等、无凭空作废")
    void rehearseYearEndOnRealData() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("leave.rehearsal"),
                "彩排会改动测试库里的生产副本, 需显式开启: -Dleave.rehearsal=true");

        List<Map<String, Object>> before = snapshotBalances();
        assertTrue(before.size() >= 30, "测试库里应当已经放好生产快照, 实际 " + before.size() + " 个账户");

        // ---- 跑真实的年终结算 ----
        tasks.cleanupExpiredLeaveBalances(String.valueOf(CLEANUP_YEAR));

        // ---- 1. 守恒: 结转值 = 期初结转 + 该年度额度 + 流水净额 ----
        //  作废的天数体现在 EXPIRED 流水里, 已包含在 movements 中。
        //  期初结转 last_year_balance 必须算进来 —— 它代表更早年度发放、结转到本年的额度,
        //  而那些年度在这份快照里没有账户记录。
        List<Map<String, Object>> mismatches = jdbc.queryForList("""
                SELECT a.user_id, u.real_name,
                       a.last_year_balance AS opening, a.actual_quota, n.carry_over,
                       COALESCE(m.movements, 0) AS movements,
                       ROUND(a.last_year_balance + a.actual_quota
                             + COALESCE(m.movements, 0) - n.carry_over, 2) AS diff
                FROM leave_account a
                JOIN sys_user u ON u.id = a.user_id
                JOIN (SELECT user_id, last_year_balance AS carry_over FROM leave_account
                      WHERE year = ? AND deleted = 0) n ON n.user_id = a.user_id
                LEFT JOIN (SELECT user_id, SUM(days) AS movements FROM leave_record
                           WHERE deleted = 0 AND type <> 'CARRY_OVER' AND start_date >= ?
                           GROUP BY user_id) m ON m.user_id = a.user_id
                WHERE a.year = ? AND a.deleted = 0
                  AND ABS(a.last_year_balance + a.actual_quota
                          + COALESCE(m.movements, 0) - n.carry_over) > 0.001
                """, CLEANUP_YEAR + 1, CLEANUP_YEAR + "-01-01", CLEANUP_YEAR);

        assertTrue(mismatches.isEmpty(), () -> "守恒校验失败的账户: " + mismatches);

        // ---- 2. 作废流水永远是负数 ----
        Long badExpiry = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_record WHERE type = 'EXPIRED' AND days > 0 AND deleted = 0",
                Long.class);
        assertEquals(0L, badExpiry, "不应出现正数的过期流水");

        // ---- 3. 幂等: 重跑两次, 结转值与流水条数都不变 ----
        Map<String, Object> after = jdbc.queryForMap(
                "SELECT COUNT(*) AS records, COALESCE(SUM(days), 0) AS net FROM leave_record WHERE deleted = 0");
        List<Map<String, Object>> carryAfterFirst = jdbc.queryForList(
                "SELECT user_id, last_year_balance FROM leave_account WHERE year = ? AND deleted = 0 ORDER BY user_id",
                CLEANUP_YEAR + 1);

        tasks.cleanupExpiredLeaveBalances(String.valueOf(CLEANUP_YEAR));
        tasks.cleanupExpiredLeaveBalances(String.valueOf(CLEANUP_YEAR));

        assertEquals(after, jdbc.queryForMap(
                "SELECT COUNT(*) AS records, COALESCE(SUM(days), 0) AS net FROM leave_record WHERE deleted = 0"),
                "重复结算不应产生新流水或改变净额");
        assertEquals(carryAfterFirst, jdbc.queryForList(
                "SELECT user_id, last_year_balance FROM leave_account WHERE year = ? AND deleted = 0 ORDER BY user_id",
                CLEANUP_YEAR + 1),
                "重复结算不应改变任何人的结转值");

        // ---- 4. 打印结果供人工核对 ----
        System.out.println("\n===== 年终结算彩排结果 (" + CLEANUP_YEAR + " → " + (CLEANUP_YEAR + 1) + ") =====");
        System.out.printf("%-10s %10s %10s %8s %8s %8s%n",
                "姓名", "期初结转", "整年额度", "作废", "结转下年", "透支");
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT u.real_name,
                       a.last_year_balance AS opening,
                       a.actual_quota,
                       COALESCE(e.expired, 0) AS expired,
                       n.last_year_balance AS carry_over,
                       COALESCE(f.floating, 0) AS floating
                FROM leave_account a
                JOIN sys_user u ON u.id = a.user_id
                JOIN leave_account n ON n.user_id = a.user_id AND n.year = ? AND n.deleted = 0
                LEFT JOIN (SELECT user_id, SUM(days) AS expired FROM leave_record
                           WHERE deleted = 0 AND type = 'EXPIRED' GROUP BY user_id) e ON e.user_id = a.user_id
                LEFT JOIN (SELECT user_id, SUM(days) AS floating FROM leave_record
                           WHERE deleted = 0 AND expiry_date IS NULL AND type <> 'CARRY_OVER'
                           GROUP BY user_id) f ON f.user_id = a.user_id
                WHERE a.year = ? AND a.deleted = 0
                ORDER BY u.id
                """, CLEANUP_YEAR + 1, CLEANUP_YEAR)) {
            System.out.printf("%-10s %10s %10s %8s %8s %8s%n",
                    row.get("real_name"), row.get("opening"), row.get("actual_quota"),
                    row.get("expired"), row.get("carry_over"), row.get("floating"));
        }

        BigDecimal totalExpired = scalar(
                "SELECT COALESCE(SUM(days), 0) FROM leave_record WHERE type = 'EXPIRED' AND deleted = 0");
        BigDecimal totalCarry = scalar(
                "SELECT COALESCE(SUM(last_year_balance), 0) FROM leave_account WHERE year = ? AND deleted = 0",
                CLEANUP_YEAR + 1);
        System.out.println("合计作废: " + totalExpired + " 天,  合计结转: " + totalCarry + " 天");
    }

    private List<Map<String, Object>> snapshotBalances() {
        return jdbc.queryForList(
                "SELECT user_id, actual_quota, last_year_balance FROM leave_account "
                        + "WHERE year = ? AND deleted = 0 ORDER BY user_id",
                CLEANUP_YEAR);
    }
}
