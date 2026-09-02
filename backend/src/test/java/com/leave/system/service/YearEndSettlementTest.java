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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ==================================================================
    @Nested
    @DisplayName("任务合并: 清理任务已包含初始化任务")
    class JobMerge {

        /**
         * 两个定时任务原本都定在 1 月 1 日 (清理 01:00, 初始化 02:00)。
         * 清理任务的编排第三步就是 {@code initAllAccounts(cleanupYear + 1)},
         * 而 1 月 1 日执行时 {@code cleanupYear + 1 == LocalDate.now().getYear()} ——
         * 与初始化任务的目标年度完全重合, 调的还是同一个方法。
         *
         * <p>
         * 下面三个用例锁住这个结论。任何一次改动若把编排里的初始化步骤删掉或改了年份,
         * 这里就会红 —— 因为那时初始化任务已经不在了, 没有任何东西兜底。
         */
        private static final int CLEANUP_YEAR = 2024;
        private static final int INIT_YEAR = CLEANUP_YEAR + 1;

        private static final long VETERAN = 1L;
        private static final long NEW_HIRE = 2L;
        private static final long RESIGNED = 3L;

        @BeforeEach
        void seedTeam() {
            // 老员工: 有上年度账户, 清理阶段能遍历到
            db.addUser(VETERAN, "veteran", LocalDate.of(2010, 1, 1), LocalDate.of(2015, 6, 1));
            leaveService.initYearlyAccount(VETERAN, CLEANUP_YEAR);
            leaveService.applyLeave(VETERAN, LocalDate.of(CLEANUP_YEAR, 3, 1),
                    LocalDate.of(CLEANUP_YEAR, 3, 1), new BigDecimal("3.0"));

            // 新员工: INIT_YEAR 才入职, 没有上年度账户。performCleanupForYear 按上年度账户
            // 遍历, 遍历不到他 —— 他的账户只能靠编排的第三步建出来。
            db.addUser(NEW_HIRE, "newhire", LocalDate.of(2018, 3, 1), LocalDate.of(INIT_YEAR, 2, 10));

            // 离职员工: 两条路径都应跳过
            SysUser quit = db.addUser(RESIGNED, "quit", LocalDate.of(2012, 1, 1), LocalDate.of(2016, 1, 1));
            leaveService.initYearlyAccount(RESIGNED, CLEANUP_YEAR);
            quit.setStatus("RESIGNED");
            quit.setResignationDate(LocalDate.of(CLEANUP_YEAR, 6, 30));
        }

        @Test
        @DisplayName("清理任务会给没有上年度账户的新员工建号 —— 这正是初始化任务的活")
        void cleanupJobAlsoCreatesAccountsForNewHires() {
            assertNull(db.account(NEW_HIRE, INIT_YEAR), "前置: 新员工此时还没有账户");

            tasks.cleanupExpiredLeaveBalances(String.valueOf(CLEANUP_YEAR));

            assertNotNull(db.account(NEW_HIRE, INIT_YEAR),
                    "清理任务必须为没有上年度账户的新员工建立 " + INIT_YEAR + " 年账户");
            assertNotNull(db.account(VETERAN, INIT_YEAR), "老员工同样要有新年度账户");
            assertNull(db.account(RESIGNED, INIT_YEAR), "离职员工不应建号");
        }

        @Test
        @DisplayName("清理任务跑完再跑初始化任务: 账户与流水零变化")
        void initJobChangesNothingAfterCleanupJob() {
            tasks.cleanupExpiredLeaveBalances(String.valueOf(CLEANUP_YEAR));

            String accountsBefore = snapshotAccounts();
            String recordsBefore = snapshotRecords();

            // 初始化任务的入口。1 月 1 日执行时无参版本解析出的年度就是 INIT_YEAR,
            // 这里显式传年份, 免得用例结果随系统时间变化。
            tasks.initAllAccounts(String.valueOf(INIT_YEAR));

            assertEquals(accountsBefore, snapshotAccounts(),
                    "初始化任务不应改动任何账户 —— 它的活清理任务已经做完了");
            assertEquals(recordsBefore, snapshotRecords(),
                    "初始化任务不应新增或改动任何流水");
        }

        @Test
        @DisplayName("反过来: 单独跑初始化任务, 建出的账户与清理任务第三步一致")
        void standaloneInitProducesTheSameAccounts() {
            tasks.cleanupExpiredLeaveBalances(String.valueOf(CLEANUP_YEAR));
            String fromCleanupJob = snapshotAccounts();

            // 把新年度账户抹掉, 只跑初始化任务
            db.dropAccounts(INIT_YEAR);
            tasks.initAllAccounts(String.valueOf(INIT_YEAR));

            assertEquals(fromCleanupJob, snapshotAccounts(),
                    "两条路径建出的账户必须逐字段一致");
        }

        private String snapshotAccounts() {
            StringBuilder sb = new StringBuilder();
            for (long uid : List.of(VETERAN, NEW_HIRE, RESIGNED)) {
                for (int y = CLEANUP_YEAR; y <= INIT_YEAR; y++) {
                    LeaveAccount a = db.account(uid, y);
                    sb.append(uid).append('/').append(y).append('=')
                            .append(a == null ? "none"
                                    : a.getSocialSeniority() + "|" + a.getStandardQuota() + "|"
                                            + a.getDaysEmployed() + "|" + a.getActualQuota() + "|"
                                            + a.getLastYearBalance())
                            .append('\n');
                }
            }
            return sb.toString();
        }

        private String snapshotRecords() {
            StringBuilder sb = new StringBuilder();
            for (long uid : List.of(VETERAN, NEW_HIRE, RESIGNED)) {
                for (LeaveRecord r : db.allRecords(uid)) {
                    sb.append(r.getId()).append('|').append(uid).append('|').append(r.getStartDate())
                            .append('|').append(r.getDays()).append('|').append(r.getType())
                            .append('|').append(r.getExpiryDate()).append('|').append(r.getRemarks())
                            .append('\n');
                }
            }
            return sb.toString();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("延迟补跑: 结果必须与准点跑一致")
    class DelayedRollover {

        /**
         * 结转是「截至上年 12/31」的快照。若算结转时把目标年度已经发生的请假也扫进来,
         * 那笔假会被扣两次 —— 一次减在 last_year_balance 里, 一次算在目标年度自己的账本里。
         *
         * <p>
         * 三条路径都会踩到:
         * <ul>
         * <li>服务器 1 月 1 日没运行, 管理员事后手动补跑年终结算;</li>
         * <li>管理员点 {@code /admin/init-all-accounts?year=YYYY} 重算账户;</li>
         * <li>准点跑也有窄缝: 编排第一步先同步钉钉, 同步窗口含 1 月 1 日当天。</li>
         * </ul>
         */
        private static final long ONTIME = 1L;
        private static final long DELAYED = 2L;

        @BeforeEach
        void twoIdenticalEmployees() {
            for (long uid : new long[] { ONTIME, DELAYED }) {
                db.addUser(uid, "emp" + uid, LocalDate.of(2010, 1, 1), LocalDate.of(2015, 6, 1));
                leaveService.initYearlyAccount(uid, 2024);
                leaveService.applyLeave(uid, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 1),
                        new BigDecimal("4.0"));
            }
        }

        @Test
        @DisplayName("服务器 1/1 没跑, 员工先请了假, 事后补跑不会把这笔假重复扣掉")
        void delayedRolloverDoesNotDoubleCountNewYearLeave() {
            // 准点: 先结算, 再请假
            tasks.cleanupExpiredLeaveBalances("2024");
            leaveService.applyLeave(ONTIME, LocalDate.of(2025, 1, 15), LocalDate.of(2025, 1, 15),
                    new BigDecimal("2.0"));

            // 延迟: 先请假, 事后才补跑结算
            leaveService.applyLeave(DELAYED, LocalDate.of(2025, 1, 15), LocalDate.of(2025, 1, 15),
                    new BigDecimal("2.0"));
            BigDecimal beforeRollover = leaveService.getAccount(DELAYED, 2025).getTotalBalance();
            tasks.cleanupExpiredLeaveBalances("2024");

            assertDays(beforeRollover, leaveService.getAccount(DELAYED, 2025).getTotalBalance());
            assertDays(leaveService.getAccount(ONTIME, 2025).getTotalBalance(),
                    leaveService.getAccount(DELAYED, 2025).getTotalBalance());
            // 2024 额度 10 - 已休 4 = 6 结转, 2025 额度 10, 已休 2 → 14
            assertDays("14.0", leaveService.getAccount(DELAYED, 2025).getTotalBalance());
        }

        @Test
        @DisplayName("管理员年中重算账户 (/admin/init-all-accounts), 不会吃掉已休的假")
        void manualReinitDoesNotEatUsedDays() {
            tasks.cleanupExpiredLeaveBalances("2024");
            leaveService.applyLeave(DELAYED, LocalDate.of(2025, 5, 20), LocalDate.of(2025, 5, 20),
                    new BigDecimal("3.0"));
            BigDecimal before = leaveService.getAccount(DELAYED, 2025).getTotalBalance();

            leaveService.initYearlyAccount(DELAYED, 2025);
            assertDays(before, leaveService.getAccount(DELAYED, 2025).getTotalBalance());

            // 连点三次也一样
            leaveService.initYearlyAccount(DELAYED, 2025);
            leaveService.initYearlyAccount(DELAYED, 2025);
            assertDays(before, leaveService.getAccount(DELAYED, 2025).getTotalBalance());
        }

        @Test
        @DisplayName("结转值本身与请假顺序无关")
        void carryOverIsIndependentOfRolloverTiming() {
            tasks.cleanupExpiredLeaveBalances("2024");
            leaveService.applyLeave(ONTIME, LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 1),
                    new BigDecimal("5.0"));

            leaveService.applyLeave(DELAYED, LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 1),
                    new BigDecimal("5.0"));
            tasks.cleanupExpiredLeaveBalances("2024");

            assertDays(db.account(ONTIME, 2025).getLastYearBalance(),
                    db.account(DELAYED, 2025).getLastYearBalance());
            assertDays("6.0", db.account(DELAYED, 2025).getLastYearBalance());
        }

        @Test
        @DisplayName("考勤周期口径: 年终结算改到 1 月 25 日执行")
        void settlementOnDay25OfJanuary() {
            // 考勤周期是上月 26 到当月 25, 所以 12/26~12/31 的假往往在 1 月才录进来。
            // 把年终结算推到 1/25, 等这批迟到的假落地之后再算定上年度 —— 前提是
            // 这 25 天里已经发生的新年度请假不能被重复扣掉。

            // 1/10: 员工请了 2 天。此时账户还没建, applyLeave 按需建号,
            //       算出来的结转是 6.0 (12/28 那笔还没录进来)
            leaveService.applyLeave(DELAYED, LocalDate.of(2025, 1, 10), LocalDate.of(2025, 1, 10),
                    new BigDecimal("2.0"));
            assertDays("6.0", db.account(DELAYED, 2025).getLastYearBalance());
            assertDays("14.0", leaveService.getAccount(DELAYED, 2025).getTotalBalance());

            // 1/15: 钉钉同步补进一笔 12/28 的假 (回溯窗口 27 天覆盖得到)
            leaveService.applyLeave(DELAYED, LocalDate.of(2024, 12, 28), LocalDate.of(2024, 12, 28),
                    new BigDecimal("1.0"));

            // 1/25: 年终结算执行
            tasks.cleanupExpiredLeaveBalances("2024");

            // 结转被修正: 2024 额度 10 - 3/1 的 4 天 - 12/28 的 1 天 = 5
            assertDays("5.0", db.account(DELAYED, 2025).getLastYearBalance());
            // 2025 余额: 结转 5 + 额度 10 - 1/10 已休 2 = 13。1/10 那 2 天只扣一次
            assertDays("13.0", leaveService.getAccount(DELAYED, 2025).getTotalBalance());

            // 守恒: 两年共发放 20 天, 流水净额 -7 天
            assertDays("20.0", db.account(DELAYED, 2024).getActualQuota()
                    .add(db.account(DELAYED, 2025).getActualQuota()));
            assertDays("-7.0", db.netRecords(DELAYED).subtract(db.sumRecords(DELAYED, "CARRY_OVER")));
        }

        @Test
        @DisplayName("1 月 25 日执行也必须幂等: 补跑当天点两次不会变")
        void settlementOnDay25IsIdempotent() {
            leaveService.applyLeave(DELAYED, LocalDate.of(2025, 1, 10), LocalDate.of(2025, 1, 10),
                    new BigDecimal("2.0"));
            leaveService.applyLeave(DELAYED, LocalDate.of(2024, 12, 28), LocalDate.of(2024, 12, 28),
                    new BigDecimal("1.0"));

            tasks.cleanupExpiredLeaveBalances("2024");
            BigDecimal once = leaveService.getAccount(DELAYED, 2025).getTotalBalance();
            int recordsOnce = db.recordCount(DELAYED);

            tasks.cleanupExpiredLeaveBalances("2024");
            tasks.cleanupExpiredLeaveBalances("2024");

            assertDays(once, leaveService.getAccount(DELAYED, 2025).getTotalBalance());
            assertEquals(recordsOnce, db.recordCount(DELAYED), "重复执行不应产生新流水");
        }

        @Test
        @DisplayName("过期额度还没被清理任务作废时请假, 扣的必须是新一年的额度")
        void expiredBucketIsNeverDeductedEvenBeforeCleanupRuns() {
            // 场景: 2023 年发的额度结转到 2024, 2024-12-31 到期。
            // 清理任务还没跑 (排到 1/25), 员工 1/10 就请了假 —— 不能扣到那笔已过期的额度上。
            long uid = 9L;
            db.addUser(uid, "expiry", LocalDate.of(2010, 1, 1), LocalDate.of(2015, 6, 1));
            leaveService.initYearlyAccount(uid, 2023);
            leaveService.initYearlyAccount(uid, 2024);

            // 2024 年的两个桶: 结转来的 10 天 12/31 到期, 当年额度 10 天 2025/12/31 到期
            assertDays("10.0", db.account(uid, 2024).getLastYearBalance());
            assertDays("10.0", db.account(uid, 2024).getActualQuota());
            assertDays("20.0", leaveService.getAccount(uid, 2024).getTotalBalance());

            // 清理任务没跑, 直接 2025-01-10 请 3 天
            leaveService.applyLeave(uid, LocalDate.of(2025, 1, 10), LocalDate.of(2025, 1, 10),
                    new BigDecimal("3.0"));

            // 结转进 2025 的只有没过期的那 10 天, 2024-12-31 那桶已经作废
            assertDays("10.0", db.account(uid, 2025).getLastYearBalance());
            // 余额 = 结转 10 + 2025 额度 10 - 已休 3 = 17 (不是 20+10-3=27)
            assertDays("17.0", leaveService.getAccount(uid, 2025).getTotalBalance());

            // 关键: 这 3 天挂在 2025-12-31 那个桶上 (2024 年发的额度), 不是已过期的 2024-12-31
            LeaveRecord leave = db.allRecords(uid).stream()
                    .filter(r -> "ANNUAL".equals(r.getType()))
                    .findFirst().orElseThrow();
            assertEquals(LocalDate.of(2025, 12, 31), leave.getExpiryDate(),
                    "请假必须扣在未过期的桶上, 实际挂在 " + leave.getExpiryDate());

            // 1/25 清理任务补跑: 补写作废流水, 但余额一分不动
            tasks.cleanupExpiredLeaveBalances("2024");
            assertDays("-10.0", db.sumRecords(uid, "EXPIRED"));
            assertDays("17.0", leaveService.getAccount(uid, 2025).getTotalBalance());
            assertDays("10.0", db.account(uid, 2025).getLastYearBalance());
        }

        @Test
        @DisplayName("补录上年度假期仍然扣得到 —— 上界没有把历史流水挡在外面")
        void backfillingLastYearLeaveStillCounts() {
            tasks.cleanupExpiredLeaveBalances("2024");
            assertDays("6.0", db.account(DELAYED, 2025).getLastYearBalance());

            // 2025 年才补录一笔 2024 年的假
            leaveService.applyLeave(DELAYED, LocalDate.of(2024, 11, 1), LocalDate.of(2024, 11, 1),
                    new BigDecimal("2.0"));
            leaveService.initYearlyAccount(DELAYED, 2025);

            assertDays("4.0", db.account(DELAYED, 2025).getLastYearBalance());
        }
    }
}