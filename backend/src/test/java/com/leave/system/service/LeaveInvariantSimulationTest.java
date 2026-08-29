package com.leave.system.service;

import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.scheduled.ScheduledTasks;
import com.leave.system.service.impl.LeaveServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 随机化多年模拟, 用不变式而不是逐条断言来验证整套账目。
 *
 * <p>
 * 每个种子跑一遍 2019~2024 六年: 随机请假、随机额度增减、每年年终结算,
 * 并在每一步校验三条必须永远成立的性质:
 * <ol>
 * <li><b>守恒</b> —— 结转值 == Σ发放 + Σ流水净额。任何一天被凭空多算/少算,
 * 或者额度过期了却没留下 EXPIRED 流水, 都会在这里暴露。</li>
 * <li><b>幂等</b> —— 年终结算重复执行不改变任何结果。</li>
 * <li><b>无凭空作废</b> —— 只有正数额度会作废; 欠账不会因为批次到期而消失。</li>
 * </ol>
 *
 * <p>
 * 模拟固定跑在已过去的年度上, 结果与运行日期无关。
 *
 * <p>
 * 提交的是 10 个代表性种子。开发时可临时把 {@code @ValueSource} 换成
 * {@code LongStream.range(0, 500)} 做宽扫 —— 这套不变式已经用变异测试验证过检出能力:
 * 把账本回溯口径改回上一年 (跨年重复计算) 500/500 全灭, 工龄改回按今天算 328/500 失败,
 * 同时撤掉两层欠账保护 27/500 失败。
 */
class LeaveInvariantSimulationTest {


    private static final long USER = 1L;
    private static final int FIRST_YEAR = 2019;
    private static final int LAST_YEAR = 2024;

    private LocalDate simFirstWork;
    private LocalDate simEntry;

    /**
     * 独立重算某个已过去年度的应发额度。
     *
     * <p>
     * <b>刻意不读 leave_account.actual_quota</b>, 只用员工档案重算。否则「发放总量」
     * 和被校验的结转值读的是同一个字段, 额度算错时两边一起错, 守恒式永远成立 ——
     * 那样的不变式是循环论证, 一条 bug 也抓不到。
     */
    private BigDecimal expectedQuota(int year) {
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        int seniority = simFirstWork.isAfter(yearEnd) ? 0
                : Period.between(simFirstWork, yearEnd).getYears();
        BigDecimal standard = new BigDecimal(seniority < 10 ? "5.0" : seniority < 20 ? "10.0" : "15.0");

        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate start = simEntry.isBefore(yearStart) ? yearStart : simEntry;
        if (start.isAfter(yearEnd)) {
            return BigDecimal.ZERO;
        }
        long employed = ChronoUnit.DAYS.between(start, yearEnd) + 1;

        return standard
                .multiply(BigDecimal.valueOf(employed))
                .divide(new BigDecimal(yearEnd.getDayOfYear()), 10, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("2"))
                .setScale(0, RoundingMode.FLOOR)
                .divide(new BigDecimal("2"), 1, RoundingMode.FLOOR);
    }

    @ParameterizedTest(name = "seed={0}")
    @ValueSource(longs = { 1L, 7L, 42L, 99L, 2024L, 31337L, 8888L, 12345L, 555L, 777L })
    @DisplayName("六年随机模拟: 守恒 / 幂等 / 欠账不作废 三条不变式恒成立")
    void invariantsHoldAcrossYears(long seed) throws Exception {
        Random rnd = new Random(seed);

        InMemoryLeaveDb db = new InMemoryLeaveDb();
        LeaveServiceImpl leaveService = new LeaveServiceImpl(
                db.accountMapper, db.recordMapper, db.userMapper, db.jobMapper);

        UserService userService = mock(UserService.class);
        when(userService.getAllUsers()).thenAnswer(c -> db.userMapper.selectAllUsers());
        ScheduledTasks tasks = new ScheduledTasks(db.recordMapper, db.accountMapper, leaveService,
                userService, mock(DingTalkService.class));
        Field self = ScheduledTasks.class.getDeclaredField("self");
        self.setAccessible(true);
        self.set(tasks, tasks);

        // 工龄档与入职日期也随机, 覆盖 5/10/15 天三档和年中入职
        LocalDate firstWork = LocalDate.of(2000 + rnd.nextInt(20), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28));
        LocalDate entry = LocalDate.of(FIRST_YEAR - rnd.nextInt(4), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28));
        db.addUser(USER, "sim", firstWork, entry);
        this.simFirstWork = firstWork;
        this.simEntry = entry;

        leaveService.initYearlyAccount(USER, FIRST_YEAR);

        for (int y0 = FIRST_YEAR; y0 <= LAST_YEAR; y0++) {
            final int year = y0;
            // --- 年内随机活动 ---
            int events = rnd.nextInt(5);
            for (int i = 0; i < events; i++) {
                int month = 1 + rnd.nextInt(12);
                int day = 1 + rnd.nextInt(28);
                switch (rnd.nextInt(4)) {
                    case 0, 1 -> // 请假 (0.5 ~ 6 天, 可能超出额度形成透支)
                        leaveService.applyLeave(USER, LocalDate.of(year, month, day),
                                LocalDate.of(year, month, day),
                                BigDecimal.valueOf((1 + rnd.nextInt(12)) * 0.5));
                    case 2 -> addRecord(leaveService, "ADJUSTMENT_ADD", year, month, day,
                            BigDecimal.valueOf((1 + rnd.nextInt(6)) * 0.5));
                    default -> addRecord(leaveService, "ADJUSTMENT_DEDUCT", year, month, day,
                            BigDecimal.valueOf((1 + rnd.nextInt(4)) * 0.5));
                }
            }

            BigDecimal expiredBefore = db.sumRecords(USER, "EXPIRED");

            // --- 年终结算 ---
            tasks.cleanupExpiredLeaveBalances(String.valueOf(year));

            assertConservation(db, year, seed);
            assertExpiryIsNeverPositive(db, seed);

            // --- 幂等: 重跑两次, 结转值与流水条数都不能变 ---
            BigDecimal carryAfterFirst = db.account(USER, year + 1).getLastYearBalance();
            int recordsAfterFirst = db.recordCount(USER);

            tasks.cleanupExpiredLeaveBalances(String.valueOf(year));
            tasks.cleanupExpiredLeaveBalances(String.valueOf(year));

            assertEquals(0, carryAfterFirst.compareTo(db.account(USER, year + 1).getLastYearBalance()),
                    () -> String.format("[seed %d] %d 年结算不幂等: 首次 %s, 重跑后 %s",
                            seed, year, carryAfterFirst, db.account(USER, year + 1).getLastYearBalance()));
            assertEquals(recordsAfterFirst, db.recordCount(USER),
                    String.format("[seed %d] %d 年结算重跑产生了新流水", seed, year));

            // 作废只会增加, 不会回退
            assertTrue(db.sumRecords(USER, "EXPIRED").compareTo(expiredBefore) <= 0,
                    String.format("[seed %d] %d 年 EXPIRED 总额异常回升", seed, year));

            assertConservation(db, year, seed);
        }
    }

    private void addRecord(LeaveServiceImpl service, String type, int year, int month, int day, BigDecimal days) {
        LeaveRecord r = new LeaveRecord();
        r.setUserId(USER);
        r.setType(type);
        r.setDays(days);
        r.setStartDate(LocalDate.of(year, month, day));
        r.setEndDate(LocalDate.of(year, month, day));
        r.setRemarks("sim");
        service.addRecord(r);
    }

    /**
     * 守恒: 截至 throughYear 年底的结转值, 必须等于
     * 「各年度发放额度之和 + 全部非结转流水的净额」。
     */
    private void assertConservation(InMemoryLeaveDb db, int throughYear, long seed) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int y = FIRST_YEAR; y <= throughYear; y++) {
            LeaveAccount a = db.account(USER, y);
            if (a == null) {
                continue;
            }
            BigDecimal shouldBe = expectedQuota(y);
            final int yy = y;
            assertEquals(0, shouldBe.compareTo(a.getActualQuota()),
                    () -> String.format("[seed %d] %d 年额度未被结算算定: 应为 %s, 库里是 %s",
                            seed, yy, shouldBe, a.getActualQuota()));
            sum = sum.add(shouldBe);
        }
        final BigDecimal granted = sum;

        BigDecimal movements = db.allRecords(USER).stream()
                .filter(r -> !"CARRY_OVER".equals(r.getType()))
                .filter(r -> r.getStartDate().getYear() <= throughYear)
                .map(LeaveRecord::getDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal expected = granted.add(movements);
        BigDecimal actual = db.account(USER, throughYear + 1).getLastYearBalance();

        assertEquals(0, expected.compareTo(actual),
                () -> String.format("[seed %d] 守恒失败 (截至 %d 年底): 发放 %s + 流水净额 %s = %s, 结转值 %s%n%s",
                        seed, throughYear, granted, movements, expected, actual, dump(db)));
    }

    /** 作废流水永远是负数(扣减), 不可能出现"过期反而加天数" */
    private void assertExpiryIsNeverPositive(InMemoryLeaveDb db, long seed) {
        List<LeaveRecord> bad = new ArrayList<>();
        for (LeaveRecord r : db.allRecords(USER)) {
            if ("EXPIRED".equals(r.getType()) && r.getDays().compareTo(BigDecimal.ZERO) > 0) {
                bad.add(r);
            }
        }
        assertTrue(bad.isEmpty(), String.format("[seed %d] 出现了正数的过期流水: %s", seed, bad));
    }

    private String dump(InMemoryLeaveDb db) {
        StringBuilder sb = new StringBuilder("--- 流水 ---\n");
        for (LeaveRecord r : db.allRecords(USER)) {
            sb.append(String.format("  %s %-18s %6s  过期:%s  %s%n",
                    r.getStartDate(), r.getType(), r.getDays(), r.getExpiryDate(), r.getRemarks()));
        }
        sb.append("--- 账户 ---\n");
        for (int y = FIRST_YEAR; y <= LAST_YEAR + 1; y++) {
            LeaveAccount a = db.account(USER, y);
            if (a != null) {
                sb.append(String.format("  %d 额度 %s, 在职 %s 天, 结转 %s%n",
                        y, a.getActualQuota(), a.getDaysEmployed(), a.getLastYearBalance()));
            }
        }
        return sb.toString();
    }
}
