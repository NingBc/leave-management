package com.leave.system.it;

import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.LeaveAccountMapper;
import com.leave.system.scheduled.ScheduledTasks;
import com.leave.system.service.LeaveService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 透支在年终结算中的处理 —— 走真实数据库 + 真实定时任务。
 *
 * <p>
 * 生产数据里只出现过一种透支形态: 欠账能被当年额度全额覆盖。这里补齐现实中尚未出现、
 * 但迟早会遇到的几种:
 * <ul>
 * <li>欠账超过可用额度 → 只能部分归位, 余下的作为负数结转</li>
 * <li>负结转跨两年 → 不能被重复计债, 也不能因为桶到期而蒸发</li>
 * <li>即将作废的结转额度应当先拿去抵债, 而不是白白作废</li>
 * <li>连续两年透支的累积</li>
 * </ul>
 *
 * <p>
 * 全部跑在已过去的年度 (2022~2024) 上, 额度按整年算定, 与运行日期无关。
 */
class OverdraftSettlementIT extends IntegrationTestBase {

    @Autowired
    LeaveService leaveService;

    @Autowired
    ScheduledTasks tasks;

    @Autowired
    LeaveAccountMapper accountMapper;

    /** 工龄 10 天档, 各年度整年在职 */
    private SysUser veteran(String tag) {
        return createTestUser(tag, 2L, LocalDate.of(2010, 1, 1), LocalDate.of(2015, 6, 1));
    }

    /** 工龄 5 天档 */
    private SysUser junior(String tag) {
        return createTestUser(tag, 2L, LocalDate.of(2018, 1, 1), LocalDate.of(2019, 6, 1));
    }

    private void takeLeave(SysUser u, int year, int month, int day, String days) {
        leaveService.applyLeave(u.getId(), LocalDate.of(year, month, day), LocalDate.of(year, month, day),
                new BigDecimal(days));
    }

    /**
     * 单个用户的年终结算 = 该年度的过期清理 + 下一年度账户初始化。
     *
     * <p>
     * 不走 {@code cleanupExpiredLeaveBalances} 的整批入口: 那个会对全部用户跑一遍
     * initAllAccounts, 在这里既慢又会给测试库里那份生产副本凭空造出一堆年度账户。
     * 整批路径由 YearEndRehearsalIT 用真实数据覆盖。
     */
    private void rollover(SysUser u, int year) {
        LeaveAccount account = accountMapper.selectAccountByUserIdAndYear(u.getId(), year);
        if (account != null) {
            tasks.cleanupUserForYear(account, LocalDate.of(year, 12, 31));
        }
        leaveService.initYearlyAccount(u.getId(), year + 1);
    }

    private BigDecimal carryOver(SysUser u, int year) {
        return scalar("SELECT last_year_balance FROM leave_account WHERE user_id = ? AND year = ? AND deleted = 0",
                u.getId(), year);
    }

    private BigDecimal quota(SysUser u, int year) {
        return scalar("SELECT actual_quota FROM leave_account WHERE user_id = ? AND year = ? AND deleted = 0",
                u.getId(), year);
    }

    /**
     * 指定年度账本里的浮动债务净额。
     *
     * <p>
     * 必须按年度限定。更早年度的透支流水会一直留在表里 —— 它们已经折进
     * last_year_balance, 只是不再参与后续年度的账本; 全历史求和会把它们重复看见。
     */
    private BigDecimal floatingDebt(SysUser u, int year) {
        return scalar("SELECT COALESCE(SUM(days), 0) FROM leave_record "
                + "WHERE user_id = ? AND deleted = 0 AND expiry_date IS NULL AND type <> 'CARRY_OVER' "
                + "AND start_date >= ?", u.getId(), year + "-01-01");
    }

    private BigDecimal expired(SysUser u) {
        return scalar("SELECT COALESCE(SUM(days), 0) FROM leave_record "
                + "WHERE user_id = ? AND deleted = 0 AND type = 'EXPIRED'", u.getId());
    }

    private static void assertDays(String expected, BigDecimal actual, String msg) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> msg + " —— 期望 " + expected + ", 实际 " + actual);
    }

    /**
     * 守恒: 截至 throughYear 年底的结转值 = 期初结转 + 各年度额度 + 全部非结转流水净额。
     */
    private void assertConservation(SysUser u, int firstYear, int throughYear) {
        BigDecimal opening = scalar(
                "SELECT COALESCE(last_year_balance, 0) FROM leave_account WHERE user_id = ? AND year = ?",
                u.getId(), firstYear);
        BigDecimal granted = scalar(
                "SELECT COALESCE(SUM(actual_quota), 0) FROM leave_account "
                        + "WHERE user_id = ? AND year BETWEEN ? AND ? AND deleted = 0",
                u.getId(), firstYear, throughYear);
        BigDecimal movements = scalar(
                "SELECT COALESCE(SUM(days), 0) FROM leave_record WHERE user_id = ? AND deleted = 0 "
                        + "AND type <> 'CARRY_OVER' AND YEAR(start_date) BETWEEN ? AND ?",
                u.getId(), firstYear, throughYear);
        BigDecimal actual = carryOver(u, throughYear + 1);

        assertEquals(0, opening.add(granted).add(movements).compareTo(actual),
                () -> String.format("守恒失败: 期初 %s + 发放 %s + 流水 %s = %s, 但结转值是 %s",
                        opening, granted, movements, opening.add(granted).add(movements), actual));
    }

    // ==================================================================

    @Test
    @DisplayName("欠账能被当年额度覆盖: 全额归位, 结转为正 (复刻生产现状)")
    void debtFullyCoveredByQuota() {
        SysUser u = veteran("od_covered");
        leaveService.initYearlyAccount(u.getId(), 2022);
        takeLeave(u, 2022, 3, 1, "13.0");          // 额度 10, 透支 3

        assertDays("-3.0", floatingDebt(u, 2022), "请假当下应留下 3 天透支");

        rollover(u, 2022);

        assertDays("10.0", quota(u, 2022), "结算时额度补算到整年");
        assertDays("-3.0", carryOver(u, 2023), "10 - 13 = -3");
        assertConservation(u, 2022, 2022);

        // 2023 年额度上来后, 下次请假时欠账归位: 负结转被当年额度补平
        takeLeave(u, 2023, 6, 1, "1.0");
        assertDays("0", floatingDebt(u, 2023), "2023 账本里不应留下新的透支");
        assertDays("0", scalar(
                "SELECT COALESCE(SUM(last_year_balance), 0) + COALESCE((SELECT SUM(days) FROM leave_record "
                        + "WHERE user_id = ? AND deleted = 0 AND type <> 'CARRY_OVER' "
                        + "AND start_date >= '2023-01-01' AND expiry_date = '2023-12-31'), 0) "
                        + "FROM leave_account WHERE user_id = ? AND year = 2023 AND deleted = 0",
                u.getId(), u.getId()),
                "负结转桶应被归位补平到 0");

        rollover(u, 2023);
        assertDays("6.0", carryOver(u, 2024), "-3 + 10 - 1 = 6");
        assertConservation(u, 2022, 2023);
    }

    @Test
    @DisplayName("欠账超过可用额度: 只归位能覆盖的部分, 余下继续背")
    void debtExceedsAvailableQuota() {
        SysUser u = junior("od_partial");           // 额度只有 5 天
        leaveService.initYearlyAccount(u.getId(), 2022);
        takeLeave(u, 2022, 3, 1, "18.0");           // 额度 5, 透支 13

        rollover(u, 2022);

        assertDays("5.0", quota(u, 2022), "");
        assertDays("-13.0", carryOver(u, 2023), "5 - 18 = -13, 负数结转");
        assertConservation(u, 2022, 2022);

        // 2023 年额度 5 天, 欠 13 → 只能抵掉 5, 还欠 8
        rollover(u, 2023);
        assertDays("-8.0", carryOver(u, 2024), "-13 + 5 = -8");
        assertDays("0", expired(u), "欠着账的人不应该有任何额度作废");
        assertConservation(u, 2022, 2023);

        // 再一年: -8 + 5 = -3
        rollover(u, 2024);
        assertDays("-3.0", carryOver(u, 2025), "");
        assertConservation(u, 2022, 2024);
    }

    @Test
    @DisplayName("欠账不会因为所属批次到期而蒸发")
    void debtSurvivesBucketExpiry() {
        SysUser u = junior("od_expiry");
        leaveService.initYearlyAccount(u.getId(), 2022);
        takeLeave(u, 2022, 3, 1, "9.0");            // 额度 5, 透支 4

        rollover(u, 2022);
        assertDays("-4.0", carryOver(u, 2023), "");

        // 2023 年一天不休。结转桶是 -4, 到 2023 年底该桶到期 ——
        // 正数额度会作废, 负数欠账必须继续背, 不能被当作"作废"抹掉。
        rollover(u, 2023);
        assertDays("1.0", carryOver(u, 2024), "-4 + 5(2023额度) = 1");
        assertDays("0", expired(u), "没有可作废的正数额度");
        assertConservation(u, 2022, 2023);
    }

    @Test
    @DisplayName("即将作废的结转额度先拿去抵债, 而不是白白作废")
    void expiringBalancePaysDebtFirst() {
        SysUser u = veteran("od_expiring_pays");
        leaveService.initYearlyAccount(u.getId(), 2022);
        rollover(u, 2022);                              // 2022 一天没休 → 10 天结转到 2023

        assertDays("10.0", carryOver(u, 2023), "");

        // 2023: 结转 10(年底作废) + 当年额度 10。请 12 天 → 先扣结转 10, 再扣当年 2
        takeLeave(u, 2023, 5, 1, "12.0");
        // 再借 3 天, 此时两个桶都还有余额吗? 结转桶已空, 当年桶还剩 8
        takeLeave(u, 2023, 6, 1, "9.0");             // 当年桶 8 → 用完, 透支 1

        assertDays("-1.0", floatingDebt(u, 2023), "超出部分应留下 1 天透支");

        rollover(u, 2023);
        // 总计: 10(结转) + 10(额度) - 21(请假) = -1
        assertDays("-1.0", carryOver(u, 2024), "");
        assertDays("0", expired(u), "额度全部被用掉, 不应有作废");
        assertConservation(u, 2022, 2023);
    }

    @Test
    @DisplayName("连续两年透支: 欠账累积且只算一次")
    void debtAccumulatesAcrossYearsWithoutDoubleCounting() {
        SysUser u = junior("od_two_years");
        leaveService.initYearlyAccount(u.getId(), 2022);
        takeLeave(u, 2022, 3, 1, "8.0");             // 额度 5 → 欠 3
        rollover(u, 2022);
        assertDays("-3.0", carryOver(u, 2023), "");

        takeLeave(u, 2023, 4, 1, "7.0");             // 额度 5, 期初 -3 → 欠 5
        rollover(u, 2023);
        assertDays("-5.0", carryOver(u, 2024), "-3 + 5 - 7 = -5");
        assertConservation(u, 2022, 2023);

        // 关键: 2022 年那笔透支流水仍在表里, 但已折进结转值, 不能再被算一次
        BigDecimal allFloatingRecords = scalar(
                "SELECT COALESCE(SUM(days), 0) FROM leave_record WHERE user_id = ? AND deleted = 0 "
                        + "AND expiry_date IS NULL AND type <> 'CARRY_OVER'", u.getId());
        rollover(u, 2024);
        assertDays("0", carryOver(u, 2025), "-5 + 5(2024额度) = 0");
        assertConservation(u, 2022, 2024);
        assertTrue(allFloatingRecords.compareTo(BigDecimal.ZERO) <= 0,
                "历史透支流水应当仍然保留在表里(只是不再重复参与计算)");
    }

    @Test
    @DisplayName("结算幂等: 有透支的账户重复结算, 结果与流水条数都不变")
    void settlementIsIdempotentWithDebt() {
        SysUser u = junior("od_idem");
        leaveService.initYearlyAccount(u.getId(), 2022);
        takeLeave(u, 2022, 3, 1, "11.0");            // 欠 6

        rollover(u, 2022);
        BigDecimal carry = carryOver(u, 2023);
        Long records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_record WHERE user_id = ? AND deleted = 0", Long.class, u.getId());

        rollover(u, 2022);
        rollover(u, 2022);

        assertDays(carry.toPlainString(), carryOver(u, 2023), "重复结算不应改变结转值");
        assertEquals(records, jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_record WHERE user_id = ? AND deleted = 0", Long.class, u.getId()),
                "重复结算不应产生新流水");
    }

    @Test
    @DisplayName("透支归位的流水必须成对出现, 净额为零")
    void normalizationRecordsAreBalanced() {
        SysUser u = veteran("od_pairs");
        leaveService.initYearlyAccount(u.getId(), 2022);
        takeLeave(u, 2022, 3, 1, "12.0");            // 欠 2
        rollover(u, 2022);
        takeLeave(u, 2023, 6, 1, "1.0");             // 触发归位
        rollover(u, 2023);

        List<Map<String, Object>> pairs = jdbc.queryForList(
                "SELECT type, days, expiry_date FROM leave_record "
                        + "WHERE user_id = ? AND deleted = 0 AND remarks LIKE '%归位%' ORDER BY id",
                u.getId());

        assertTrue(pairs.size() >= 2, "应当写入成对的归位流水, 实际: " + pairs);
        BigDecimal net = pairs.stream()
                .map(r -> (BigDecimal) r.get("days"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertDays("0", net, "归位流水净额必须为零 —— 它只挪动欠账所在的桶, 不改变总余额");
        assertConservation(u, 2022, 2023);
    }
}
