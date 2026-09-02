package com.leave.system.it;

import com.leave.system.entity.SysUser;
import com.leave.system.scheduled.ScheduledTasks;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「年假过期清理」任务是否完全覆盖「年假账户批量初始化」任务。
 *
 * <p>
 * 两个任务原本都定在 1 月 1 日: 清理 01:00, 初始化 03:00。清理任务的编排
 * ({@code runYearEndRollover}) 第三步就是 {@code initAllAccounts(cleanupYear + 1)},
 * 而 1 月 1 日执行时 {@code cleanupYear + 1} 恰好等于初始化任务的
 * {@code LocalDate.now().getYear()} —— 两者调用的是同一个方法、同一个年份。
 *
 * <p>
 * 本用例用生产快照实测这个结论, 覆盖三点:
 * <ol>
 * <li><b>幂等</b>: 清理任务跑完之后再跑一次初始化任务, 账户与流水一个字节都不变;</li>
 * <li><b>覆盖面</b>: 没有上年度账户的新员工(初始化任务的主要价值), 清理任务同样会给他建号;</li>
 * <li><b>离职人员</b>: 两条路径都跳过, 行为一致。</li>
 * </ol>
 *
 * <p>
 * 前提与 {@link YearEndRehearsalIT} 相同: 测试库里放好生产快照并整体前移一年。
 * <pre>
 *   mvn -o test -Dtest=JobEquivalenceIT -Dleave.rehearsal=true
 * </pre>
 */
class JobEquivalenceIT extends IntegrationTestBase {

    private static final int CLEANUP_YEAR = 2025;
    private static final int INIT_YEAR = CLEANUP_YEAR + 1;

    @Autowired
    ScheduledTasks tasks;

    @Test
    @DisplayName("清理任务跑完后再跑初始化任务: 数据零变化")
    void initJobIsRedundantAfterCleanupJob() {
        Assumptions.assumeTrue(Boolean.getBoolean("leave.rehearsal"),
                "会改动测试库里的生产副本, 需显式开启: -Dleave.rehearsal=true");
        assertEquals(INIT_YEAR, LocalDate.now().getYear(),
                "彩排快照必须前移到使 cleanupYear+1 == 今年, 否则无法模拟 1 月 1 日两个任务的年份重合");

        // 新员工: 只有今年入职, 没有上年度账户。performCleanupForYear 按上年度账户遍历,
        // 遍历不到他 —— 他的账户只能由编排的第三步(初始化)建出来。
        SysUser newHire = createTestUser("newhire", 2L,
                LocalDate.of(2018, 3, 1), LocalDate.of(INIT_YEAR, 2, 10));
        SysUser resigned = createTestUser("resigned", 2L,
                LocalDate.of(2015, 5, 1), LocalDate.of(2019, 5, 1));
        jdbc.update("UPDATE sys_user SET status = 'RESIGNED', resignation_date = ? WHERE id = ?",
                LocalDate.of(CLEANUP_YEAR, 6, 30), resigned.getId());

        // ---- 只跑清理任务 ----
        tasks.cleanupExpiredLeaveBalances(String.valueOf(CLEANUP_YEAR));

        // 覆盖面: 新员工被清理任务建了号
        assertNotNull(accountOf(newHire.getId(), INIT_YEAR),
                "清理任务应当为没有上年度账户的新员工建立 " + INIT_YEAR + " 年账户");
        // 离职人员被跳过
        assertEquals(null, accountOf(resigned.getId(), INIT_YEAR),
                "离职人员不应被建号");

        // 所有在职用户都有了新年度账户
        Long missing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_user u
                WHERE u.deleted = 0 AND u.status <> 'RESIGNED'
                  AND NOT EXISTS (SELECT 1 FROM leave_account a
                                  WHERE a.user_id = u.id AND a.year = ? AND a.deleted = 0)
                """, Long.class, INIT_YEAR);
        assertEquals(0L, missing, "清理任务跑完后不应有在职用户缺少 " + INIT_YEAR + " 年账户");

        // ---- 快照 ----
        List<Map<String, Object>> accountsBefore = snapshotAccounts();
        List<Map<String, Object>> recordsBefore = snapshotRecords();
        assertTrue(accountsBefore.size() >= 60,
                "应当有 " + CLEANUP_YEAR + "/" + INIT_YEAR + " 两个年度的账户, 实际 " + accountsBefore.size());

        // ---- 再跑初始化任务(原 3 号任务的入口, 无参 = 当前年度) ----
        tasks.initAllAccounts();

        assertEquals(accountsBefore, snapshotAccounts(),
                "初始化任务不应改动任何账户 —— 说明它的工作清理任务已经做完了");
        assertEquals(recordsBefore, snapshotRecords(),
                "初始化任务不应新增或改动任何流水");

        // ---- 反向确认: 单独跑初始化任务, 结果与清理任务的第三步一致 ----
        // 先把新年度账户全删掉, 只跑初始化任务, 再比对
        jdbc.update("DELETE FROM leave_account WHERE year = ?", INIT_YEAR);
        tasks.initAllAccounts();
        assertEquals(onlyInitYear(accountsBefore), onlyInitYear(snapshotAccounts()),
                "单独跑初始化任务重建出的账户, 应与清理任务第三步建出的完全一致");
    }

    private List<Map<String, Object>> onlyInitYear(List<Map<String, Object>> rows) {
        return rows.stream().filter(r -> INIT_YEAR == ((Number) r.get("year")).intValue()).toList();
    }

    private Map<String, Object> accountOf(Long userId, int year) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM leave_account WHERE user_id = ? AND year = ? AND deleted = 0", userId, year);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> snapshotAccounts() {
        return jdbc.queryForList("""
                SELECT user_id, year, social_seniority, standard_quota, days_employed,
                       actual_quota, last_year_balance, deleted
                FROM leave_account ORDER BY user_id, year
                """);
    }

    private List<Map<String, Object>> snapshotRecords() {
        return jdbc.queryForList("""
                SELECT id, user_id, start_date, end_date, days, type, remarks, expiry_date, deleted
                FROM leave_record ORDER BY id
                """);
    }
}
