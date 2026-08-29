package com.leave.system.service;

import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.scheduled.ScheduledTasks;
import com.leave.system.service.impl.LeaveServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 年终结算全链路测试。
 *
 * <p>
 * 用 {@link InMemoryLeaveDb} 跑真实的 {@code LeaveServiceImpl} + {@code ScheduledTasks},
 * 覆盖「请假 → 年终清理 → 跨年结转 → 下一年再请假」的完整循环。
 *
 * <p>
 * 所有场景都跑在<b>已过去的年度</b> (2023/2024/2025) 上, 这样
 * {@code quotaReferenceDate} 恒为该年度 12/31, 结果与运行日期无关, 测试不会随时间变红。
 */
class YearEndSettlementTest {

    private static final long USER = 1L;

    private InMemoryLeaveDb db;
    private LeaveServiceImpl leaveService;
    private ScheduledTasks tasks;

    @BeforeEach
    void setUp() throws Exception {
        db = new InMemoryLeaveDb();
        leaveService = new LeaveServiceImpl(db.accountMapper, db.recordMapper, db.userMapper, db.jobMapper);

        UserService userService = mock(UserService.class);
        org.mockito.Mockito.when(userService.getAllUsers())
                .thenAnswer(c -> db.userMapper.selectAllUsers());
        DingTalkService dingTalk = mock(DingTalkService.class);

        tasks = new ScheduledTasks(db.recordMapper, db.accountMapper, leaveService, userService, dingTalk);
        // self 是 @Autowired 的代理引用, 单测里指回自身即可 (只影响事务传播, 不影响逻辑)
        Field self = ScheduledTasks.class.getDeclaredField("self");
        self.setAccessible(true);
        self.set(tasks, tasks);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** 老员工: 2010 年参加工作(工龄档 10 天), 2015 年入职本公司, 各年度整年在职 */
    private void givenVeteran() {
        db.addUser(USER, "veteran", LocalDate.of(2010, 1, 1), LocalDate.of(2015, 6, 1));
    }

    private void takeLeave(int year, int month, int day, String days) {
        leaveService.applyLeave(USER, LocalDate.of(year, month, day), LocalDate.of(year, month, day),
                new BigDecimal(days));
    }

    /** 跑一次年终结算: 清理 cleanupYear 的过期额度, 并初始化 cleanupYear+1 */
    private void rollover(int cleanupYear) {
        tasks.cleanupExpiredLeaveBalances(String.valueOf(cleanupYear));
    }

    private BigDecimal balance(int year) {
        return leaveService.getAccount(USER, year).getTotalBalance();
    }

    private BigDecimal carryOver(int year) {
        LeaveAccount a = db.account(USER, year);
        assertNotNull(a, "年度 " + year + " 的账户应当存在");
        return a.getLastYearBalance();
    }

    private BigDecimal quota(int year) {
        LeaveAccount a = db.account(USER, year);
        assertNotNull(a, "年度 " + year + " 的账户应当存在");
        return a.getActualQuota();
    }

    private static void assertDays(String expected, BigDecimal actual) {
        assertDays(new BigDecimal(expected), actual);
    }

    private static void assertDays(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    /**
     * 指定年度账本里某个到期桶的净余额。
     * 桶 = 账户上的基数 (结转桶取 last_year_balance, 当年额度桶取 actual_quota)
     *     + 该年度起所有落在这个到期日上的流水。
     */
    private BigDecimal bucketBalance(int year, LocalDate expiry) {
        LeaveAccount a = db.account(USER, year);
        BigDecimal base = BigDecimal.ZERO;
        if (LocalDate.of(year, 12, 31).equals(expiry)) {
            base = a.getLastYearBalance();
        } else if (LocalDate.of(year + 1, 12, 31).equals(expiry)) {
            base = a.getActualQuota();
        }
        BigDecimal fromRecords = db.allRecords(USER).stream()
                .filter(r -> !"CARRY_OVER".equals(r.getType()))
                .filter(r -> r.getStartDate().getYear() >= year)
                .filter(r -> java.util.Objects.equals(expiry, r.getExpiryDate()))
                .map(LeaveRecord::getDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return base.add(fromRecords);
    }

    /**
     * 守恒校验 —— 这是整套逻辑最关键的不变式。
     *
     * <p>
     * 截至 {@code throughYear} 年底, 员工手上应当剩下:
     * <pre>
     *   Σ 各年度发放额度  +  Σ 额度增加  -  Σ 请假  -  Σ 过期作废  -  Σ 额度扣除
     * </pre>
     * 这个式子完全独立于分桶实现: 任何一天被凭空多算、少算, 或者过期了却没留下
     * EXPIRED 流水, 都会在这里对不上。CARRY_OVER 是纯记账留痕, 不计入。
     */
    private void assertConservation(int throughYear) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int y = 2000; y <= throughYear; y++) {
            LeaveAccount a = db.account(USER, y);
            if (a != null && a.getActualQuota() != null) {
                sum = sum.add(a.getActualQuota());
            }
        }
        final BigDecimal granted = sum;

        BigDecimal movements = db.allRecords(USER).stream()
                .filter(r -> !"CARRY_OVER".equals(r.getType()))
                .filter(r -> r.getStartDate().getYear() <= throughYear)
                .map(LeaveRecord::getDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal expected = granted.add(movements);
        BigDecimal actual = carryOver(throughYear + 1);

        assertEquals(0, expected.compareTo(actual),
                () -> String.format("守恒校验失败 (截至 %d 年底): 发放 %s + 流水净额 %s = %s, 但结转值是 %s",
                        throughYear, granted, movements, expected, actual));
    }

    // ==================================================================
    @Nested
    @DisplayName("三年完整生命周期")
    class FullLifecycle {

        @Test
        @DisplayName("请假 → 结转 → 再请假 → 未用完的部分第二年底作废")
        void threeYearCycle() {
            givenVeteran();
            leaveService.initYearlyAccount(USER, 2023);

            // --- 2023: 额度 10, 请 4 天 ---
            assertDays("10.0", quota(2023));
            assertDays("0.0", carryOver(2023));
            takeLeave(2023, 3, 1, "4.0");
            assertDays("6.0", balance(2023));

            // --- 2023 年终结算 ---
            rollover(2023);
            assertDays("6.0", carryOver(2024));      // 剩余 6 天结转
            assertDays("10.0", quota(2024));
            assertDays("16.0", balance(2024));
            assertConservation(2023);

            // --- 2024: 请 8 天, 应当先扣即将作废的结转额度 ---
            takeLeave(2024, 5, 1, "8.0");
            assertDays("8.0", balance(2024));

            List<LeaveRecord> usage2024 = db.allRecords(USER).stream()
                    .filter(r -> "ANNUAL".equals(r.getType()) && r.getStartDate().getYear() == 2024)
                    .toList();
            assertDays("-6.0", sumWithExpiry(usage2024, LocalDate.of(2024, 12, 31))); // 先用结转
            assertDays("-2.0", sumWithExpiry(usage2024, LocalDate.of(2025, 12, 31))); // 再用当年

            // --- 2024 年终结算: 结转额度正好用光, 不应产生过期 ---
            rollover(2024);
            assertDays("8.0", carryOver(2025));
            assertDays("0", db.sumRecords(USER, "EXPIRED"));
            assertConservation(2024);

            // --- 2025: 一天没休 ---
            assertDays("18.0", balance(2025));

            // --- 2025 年终结算: 结转过来的 8 天到期作废 ---
            rollover(2025);
            assertDays("-8.0", db.sumRecords(USER, "EXPIRED"));
            assertDays("10.0", carryOver(2026));   // 只剩 2025 年自己的额度
            assertConservation(2025);
        }

        private BigDecimal sumWithExpiry(List<LeaveRecord> records, LocalDate expiry) {
            return records.stream()
                    .filter(r -> expiry.equals(r.getExpiryDate()))
                    .map(LeaveRecord::getDays)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("透支")
    class Overdraft {

        @Test
        @DisplayName("欠账不会因为桶到期就一笔勾销, 必须继续背到下一年")
        void debtDoesNotExpire() {
            givenVeteran();
            leaveService.initYearlyAccount(USER, 2023);

            // 额度 10, 一口气请 13 天 → 10 天有额度, 3 天透支
            takeLeave(2023, 3, 1, "13.0");
            assertDays("-3.0", balance(2023));

            rollover(2023);
            assertDays("-3.0", carryOver(2024));   // 负数结转
            assertConservation(2023);

            // 2024: 额度 10, 背着 3 天欠账 → 可用 7
            assertDays("7.0", balance(2024));
            takeLeave(2024, 5, 1, "2.0");
            assertDays("5.0", balance(2024));

            // 2024 年终: 结转桶是 -3 (欠账), 到期时不能被当作"额度作废"丢掉
            rollover(2024);
            assertDays("5.0", carryOver(2025));
            assertDays("0", db.sumRecords(USER, "EXPIRED"));
            assertConservation(2024);
        }

        @Test
        @DisplayName("额度累计上来之后, 历史透支在下次请假时自动归位, 余额不变")
        void debtIsNormalizedOnNextDeduction() {
            givenVeteran();
            leaveService.initYearlyAccount(USER, 2023);
            takeLeave(2023, 3, 1, "13.0");        // 3 天透支

            rollover(2023);                        // 结转 -3
            BigDecimal before = balance(2024);
            assertDays("7.0", before);

            takeLeave(2024, 6, 1, "1.0");

            // 归位不改变余额, 只是把欠账挪到有额度的桶上
            assertDays("6.0", balance(2024));

            // 归位之后 2024 账本里不该再有负数桶: 负结转已被当年额度补平,
            // 也没有新的浮动透支 (2023 那几条透支流水仍在表里, 但早已折进
            // last_year_balance, 不再参与 2024 的账本)
            assertDays("0", bucketBalance(2024, LocalDate.of(2024, 12, 31)));
            assertDays("0", bucketBalance(2024, null));

            rollover(2024);
            YearEndSettlementTest.this.assertConservation(2024);
        }

        @Test
        @DisplayName("带着负结转把当年额度用光, 应当明确产生透支流水")
        void negativeCarryOverIsNettedBeforeAllocation() {
            givenVeteran();
            leaveService.initYearlyAccount(USER, 2023);
            takeLeave(2023, 3, 1, "13.0");   // 欠 3 天
            rollover(2023);
            assertDays("-3.0", carryOver(2024));

            // 额度 10, 欠 3, 真实可用 7 —— 请 10 天应当有 3 天落到透支
            takeLeave(2024, 6, 1, "10.0");
            assertDays("-3.0", balance(2024));
            assertTrue(db.allRecords(USER).stream()
                    .anyMatch(r -> r.getStartDate().getYear() == 2024
                            && "ANNUAL".equals(r.getType())
                            && r.getExpiryDate() == null),
                    "超出真实可用额度的部分必须留下透支流水");

            rollover(2024);
            YearEndSettlementTest.this.assertConservation(2024);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("幂等性")
    class Idempotency {

        @Test
        @DisplayName("年终结算重复执行, 结果完全不变")
        void rollingOverTwiceChangesNothing() {
            givenVeteran();
            leaveService.initYearlyAccount(USER, 2023);
            takeLeave(2023, 3, 1, "4.0");

            rollover(2023);
            BigDecimal carryAfterFirst = carryOver(2024);
            int recordsAfterFirst = db.recordCount(USER);

            rollover(2023);
            rollover(2023);

            assertDays(carryAfterFirst, carryOver(2024));
            assertEquals(recordsAfterFirst, db.recordCount(USER),
                    "重复结算不应产生任何新流水");
        }

        @Test
        @DisplayName("有过期作废时重复执行, 也不会重复作废")
        void expiryIsNotAppliedTwice() {
            givenVeteran();
            leaveService.initYearlyAccount(USER, 2023);
            rollover(2023);            // 10 天全部结转到 2024
            assertDays("10.0", carryOver(2024));

            rollover(2024);            // 2024 一天没休 → 结转的 10 天作废
            assertDays("-10.0", db.sumRecords(USER, "EXPIRED"));
            BigDecimal carryAfterFirst = carryOver(2025);

            rollover(2024);
            assertDays("-10.0", db.sumRecords(USER, "EXPIRED"));
            assertDays(carryAfterFirst, carryOver(2025));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("额度调整")
    class Adjustments {

        @Test
        @DisplayName("奖励额度跨年结转一次, 不重复计入")
        void adjustmentAddCountedOnce() {
            givenVeteran();
            leaveService.initYearlyAccount(USER, 2023);

            LeaveRecord bonus = new LeaveRecord();
            bonus.setUserId(USER);
            bonus.setType("ADJUSTMENT_ADD");
            bonus.setDays(new BigDecimal("3.0"));
            bonus.setStartDate(LocalDate.of(2023, 6, 1));
            bonus.setEndDate(LocalDate.of(2023, 6, 1));
            bonus.setRemarks("项目奖励");
            leaveService.addRecord(bonus);

            assertDays("13.0", balance(2023));    // 10 额度 + 3 奖励

            rollover(2023);
            assertDays("13.0", carryOver(2024));  // 奖励只算一次
            assertConservation(2023);

            // 2024 请 13 天应当刚好用完结转, 不产生透支
            takeLeave(2024, 5, 1, "13.0");
            assertDays("10.0", balance(2024));    // 只剩 2024 自己的额度
            assertTrue(db.allRecords(USER).stream()
                    .filter(r -> r.getStartDate().getYear() == 2024)
                    .noneMatch(r -> r.getExpiryDate() == null && "ANNUAL".equals(r.getType())),
                    "额度充足时不应产生透支流水");
        }

        @Test
        @DisplayName("额度扣除走优先级逻辑, 落到正确的到期桶")
        void adjustmentDeductUsesBuckets() {
            givenVeteran();
            leaveService.initYearlyAccount(USER, 2023);
            rollover(2023);                       // 10 天结转到 2024, 到期日 2024-12-31

            LeaveRecord penalty = new LeaveRecord();
            penalty.setUserId(USER);
            penalty.setType("ADJUSTMENT_DEDUCT");
            penalty.setDays(new BigDecimal("2.0"));
            penalty.setStartDate(LocalDate.of(2024, 3, 1));
            penalty.setEndDate(LocalDate.of(2024, 3, 1));
            penalty.setRemarks("冲销");
            leaveService.addRecord(penalty);

            assertDays("18.0", balance(2024));    // 10 结转 + 10 额度 - 2

            LeaveRecord written = db.allRecords(USER).stream()
                    .filter(r -> "ADJUSTMENT_DEDUCT".equals(r.getType()))
                    .findFirst().orElseThrow();
            assertEquals(LocalDate.of(2024, 12, 31), written.getExpiryDate(),
                    "应当先扣即将作废的结转额度");
            assertDays("-2.0", written.getDays());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("在职天数与工龄")
    class QuotaCalculation {

        @Test
        @DisplayName("年中入职: 按入职日折算, 年终结算时算定")
        void midYearHireIsProrated() {
            // 2023-07-01 入职, 当年在职 184 天; 工龄档 5 天 → 5 * 184/365 = 2.52 → 向下取整 2.5
            db.addUser(USER, "newbie", LocalDate.of(2023, 7, 1), LocalDate.of(2023, 7, 1));
            leaveService.initYearlyAccount(USER, 2023);

            assertDays("2.5", quota(2023));
            assertEquals(184, db.account(USER, 2023).getDaysEmployed());

            rollover(2023);
            assertDays("2.5", carryOver(2024));
            assertDays("5.0", quota(2024));       // 第二年整年在职
        }

        @Test
        @DisplayName("年终结算会把年中冻结的残值补算到整年")
        void staleQuotaIsSettledAtYearEnd() {
            givenVeteran();
            // 模拟线上现状: 账户停在年中某次访问时写下的残值
            db.seedAccount(USER, 2023, "10.0", "4.5", "0.0", 165);

            rollover(2023);

            assertDays("10.0", quota(2023));      // 补算到整年
            assertEquals(365, db.account(USER, 2023).getDaysEmployed());
            assertDays("10.0", carryOver(2024));  // 结转基数用的是补算后的值
        }

        @Test
        @DisplayName("只跑初始化不跑清理时, 结转也会先把上年额度补算到整年")
        void initAloneAlsoSettlesPriorYearQuota() {
            // 覆盖 initAllAccounts 单独执行 / 管理员手工初始化的路径 ——
            // 这条路不经过年终清理, 补算只能发生在结转计算里
            givenVeteran();
            db.seedAccount(USER, 2023, "10.0", "4.5", "0.0", 165);   // 停在年中的残值

            leaveService.initYearlyAccount(USER, 2024);

            assertDays("10.0", quota(2023));
            assertEquals(365, db.account(USER, 2023).getDaysEmployed());
            assertDays("10.0", carryOver(2024));
        }

        @Test
        @DisplayName("工龄跨档按该年度 12/31 判定, 重算历史年度不会套用今天的工龄")
        void seniorityIsRelativeToTheYear() {
            // 2014-06-01 参加工作: 2023-12-31 满 9 年 → 5 天; 2024-12-31 满 10 年 → 10 天
            db.addUser(USER, "crosser", LocalDate.of(2014, 6, 1), LocalDate.of(2015, 1, 1));

            leaveService.initYearlyAccount(USER, 2023);
            assertDays("5.0", quota(2023));
            assertEquals(9, db.account(USER, 2023).getSocialSeniority());

            leaveService.initYearlyAccount(USER, 2024);
            assertDays("10.0", quota(2024));
            assertEquals(10, db.account(USER, 2024).getSocialSeniority());

            // 再算一次 2023, 不应被 2024 的工龄档污染
            leaveService.initYearlyAccount(USER, 2023);
            assertDays("5.0", quota(2023));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("边界与回归")
    class EdgeCases {

        @Test
        @DisplayName("没有上年度账户时结转为 0, 不报错")
        void noPriorYearAccount() {
            givenVeteran();
            leaveService.initYearlyAccount(USER, 2024);
            assertDays("0.0", carryOver(2024));
            assertDays("10.0", balance(2024));
        }

        @Test
        @DisplayName("年终清理对没有任何流水的账户是安全的")
        void cleanupWithNoRecords() {
            givenVeteran();
            leaveService.initYearlyAccount(USER, 2023);
            rollover(2023);
            assertDays("10.0", carryOver(2024));
            assertDays("0", db.sumRecords(USER, "EXPIRED"));
        }

        @Test
        @DisplayName("跨年后回填上年 12 月的请假, 扣的是上年度的桶")
        void backdatedLeaveHitsThePriorYearBucket() {
            givenVeteran();
            leaveService.initYearlyAccount(USER, 2023);
            rollover(2023);                       // 2024 结转 10

            // 结算之后才同步到的 2023-12-28 请假
            takeLeave(2023, 12, 28, "2.0");

            LeaveRecord backdated = db.allRecords(USER).stream()
                    .filter(r -> LocalDate.of(2023, 12, 28).equals(r.getStartDate()))
                    .findFirst().orElseThrow();
            assertEquals(LocalDate.of(2024, 12, 31), backdated.getExpiryDate(),
                    "2023 年的额度到期日是 2024-12-31");
            assertDays("8.0", balance(2023));

            // 重跑结算后, 2024 的结转应当反映这 2 天
            rollover(2023);
            assertDays("8.0", carryOver(2024));
        }

        @Test
        @DisplayName("结算不依赖账户里已有的 last_year_balance, 而是每次重算")
        void carryOverIsRecomputedNotAccumulated() {
            givenVeteran();
            leaveService.initYearlyAccount(USER, 2023);
            takeLeave(2023, 3, 1, "4.0");
            rollover(2023);
            assertDays("6.0", carryOver(2024));

            // 人为把结转值改错, 再跑一次结算应当纠正回来
            LeaveAccount broken = db.account(USER, 2024);
            broken.setLastYearBalance(new BigDecimal("99.0"));
            db.accountMapper.updateAccount(broken);
            assertDays("99.0", carryOver(2024));

            rollover(2023);
            assertDays("6.0", carryOver(2024));
        }

        @Test
        @DisplayName("离职员工离职当年的账户与历史都保留, 且仍会被年终结算覆盖")
        void resignedEmployeeKeepsHistory() {
            givenVeteran();
            leaveService.initYearlyAccount(USER, 2023);
            leaveService.initYearlyAccount(USER, 2024);
            takeLeave(2023, 3, 1, "4.0");

            SysUser user = db.userMapper.selectUserById(USER);
            user.setResignationDate(LocalDate.of(2023, 9, 30));
            leaveService.settleResignation(USER, LocalDate.of(2023, 9, 30));

            assertNotNull(db.account(USER, 2023), "离职当年的账户必须保留");
            org.junit.jupiter.api.Assertions.assertNull(db.account(USER, 2024),
                    "离职年度之后的账户应当清理");
        }
    }
}
