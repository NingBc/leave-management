package com.leave.system.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leave.system.dto.CarryOverExpiryDTO;
import com.leave.system.dto.LeaveAccountDTO;
import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.scheduled.ScheduledTasks;
import com.leave.system.service.impl.LeaveServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 生产快照回放: 把只读导出的真实数据装进内存假库, 在假时钟下跑真实的业务代码。
 *
 * <p>
 * 全程<b>不连任何数据库</b> —— 快照是事先用只读会话导出的 JSON 行文件, 这里只读文件。
 * 用来回答两类问题, 这两类是靠手造数据的单测和连测试库的 IT 都答不了的:
 * <ul>
 * <li>页面上的余额拆分 (结转剩余 / 今年剩余 / 透支) 在<b>真实的人</b>身上对不对</li>
 * <li>跨年那一刻 (1 月 1 日、1 月 26 日两次任务) 会对真实数据做什么</li>
 * </ul>
 *
 * <p>
 * 快照含员工姓名, 不要提交进仓库。默认跳过, 显式指定目录才运行:
 * <pre>
 *   mvn -o test -Dtest=SnapshotRehearsalTest -Dleave.snapshot=/path/to/snap
 * </pre>
 * 目录里要有 users.jsonl / accounts.jsonl / records.jsonl, 结果表写到其下的 out/。
 */
class SnapshotRehearsalTest {

    private static final String SNAP = System.getProperty("leave.snapshot");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static final int YEAR = 2026;
    private static final LocalDate YEAR_END = LocalDate.of(YEAR, 12, 31);

    private InMemoryLeaveDb db;
    private LeaveServiceImpl service;
    private ScheduledTasks tasks;
    private final Map<Long, String> names = new LinkedHashMap<>();

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    @BeforeEach
    void loadSnapshot() throws Exception {
        assumeTrue(SNAP != null, "需要 -Dleave.snapshot=<快照目录>");

        db = new InMemoryLeaveDb();
        for (JsonNode n : read("users.jsonl")) {
            SysUser u = new SysUser();
            u.setId(n.get("id").asLong());
            u.setUsername("u" + u.getId());
            u.setRealName(text(n, "realName"));
            u.setEmployeeNumber(text(n, "employeeNumber"));
            u.setStatus(text(n, "status"));
            u.setFirstWorkDate(date(n, "firstWorkDate"));
            u.setEntryDate(date(n, "entryDate"));
            u.setResignationDate(date(n, "resignationDate"));
            u.setDeleted(n.get("deleted").asInt());
            db.load(u);
            names.put(u.getId(), u.getRealName());
        }
        for (JsonNode n : read("accounts.jsonl")) {
            LeaveAccount a = new LeaveAccount();
            a.setId(n.get("id").asLong());
            a.setUserId(n.get("userId").asLong());
            a.setYear(n.get("year").asInt());
            a.setSocialSeniority(n.get("socialSeniority").asInt());
            a.setStandardQuota(dec(n, "standardQuota"));
            a.setDaysEmployed(n.get("daysEmployed").asInt());
            a.setActualQuota(dec(n, "actualQuota"));
            a.setLastYearBalance(dec(n, "lastYearBalance"));
            a.setDeleted(n.get("deleted").asInt());
            db.load(a);
        }
        for (JsonNode n : read("records.jsonl")) {
            LeaveRecord r = new LeaveRecord();
            r.setId(n.get("id").asLong());
            r.setUserId(n.get("userId").asLong());
            r.setStartDate(date(n, "startDate"));
            r.setEndDate(date(n, "endDate"));
            r.setDays(dec(n, "days"));
            r.setType(text(n, "type"));
            r.setExpiryDate(date(n, "expiryDate"));
            r.setRemarks(text(n, "remarks"));
            r.setCreateTime(LocalDateTime.parse(text(n, "createTime").replace(' ', 'T')));
            r.setDeleted(n.get("deleted").asInt());
            db.load(r);
        }

        service = new LeaveServiceImpl(db.accountMapper, db.recordMapper, db.userMapper, db.jobMapper);

        UserService userService = mock(UserService.class);
        when(userService.getAllUsers()).thenAnswer(c -> db.userMapper.selectAllUsers());
        tasks = new ScheduledTasks(db.recordMapper, db.accountMapper, service, userService,
                mock(DingTalkService.class));
        Field self = ScheduledTasks.class.getDeclaredField("self");
        self.setAccessible(true);
        self.set(tasks, tasks);
    }

    // ------------------------------------------------------------------
    // 今天: 页面上的拆分对不对
    // ------------------------------------------------------------------

    @Test
    @DisplayName("今天的余额拆分: 三项之和 = 当前可休, 各项不越界")
    void todayBreakdown() throws Exception {
        List<String> rows = new ArrayList<>();
        int activeWithCarry = 0;
        BigDecimal carrySum = BigDecimal.ZERO;

        for (SysUser u : db.userMapper.selectActiveUsers()) {
            LeaveAccountDTO dto = service.getAccount(u.getId(), YEAR);
            assertInvariants(dto, u.getRealName());
            rows.add(String.join("\t", String.valueOf(u.getId()), u.getEmployeeNumber(), u.getRealName(),
                    s(dto.getTotalBalance()), s(dto.getCarryOverRemaining()), s(dto.getCurrentQuotaRemaining()),
                    s(dto.getFloatingDebt()), s(dto.getCurrentYearUsed()), s(dto.getCarryOverExpired()),
                    s(dto.getActualQuota()), s(dto.getLastYearBalance())));
            if (dto.getCarryOverRemaining().signum() > 0) {
                activeWithCarry++;
                carrySum = carrySum.add(dto.getCarryOverRemaining());
            }
        }
        write("today.tsv", rows);

        CarryOverExpiryDTO summary = service.getCarryOverExpiry(YEAR);
        System.out.printf("[今天] 汇总接口: %d 人 共 %s 天, 距作废 %d 天%n",
                summary.getUserCount(), summary.getTotalDays(), summary.getDaysLeft());
        assertEquals(activeWithCarry, summary.getUserCount(), "汇总人数应等于逐人 DTO 里结转>0 的人数");
        assertEquals(0, carrySum.compareTo(summary.getTotalDays()), "汇总天数应等于逐人结转剩余之和");

        // 「只看这些人」: 全部分页取出, 顺序应是剩余从多到少, 人数应等于汇总
        List<LeaveAccountDTO> filtered = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            List<LeaveAccountDTO> part = service.getAllAccountsPage(YEAR, page, 10, true).getRecords();
            filtered.addAll(part);
            if (part.size() < 10) {
                break;
            }
        }
        assertEquals(summary.getUserCount(), filtered.size());
        for (int i = 1; i < filtered.size(); i++) {
            assertTrue(filtered.get(i - 1).getCarryOverRemaining()
                    .compareTo(filtered.get(i).getCarryOverRemaining()) >= 0, "应按结转剩余从多到少排序");
        }
    }

    // ------------------------------------------------------------------
    // 跨年: 2027-01-01 01:00 的第一次年终任务
    // ------------------------------------------------------------------

    @Test
    @DisplayName("2027-01-01 跨年结算: 守恒、无异常、重跑幂等")
    void rolloverAtNewYear() throws Exception {
        Map<Long, BigDecimal> opening = new LinkedHashMap<>();
        for (SysUser u : db.userMapper.selectActiveUsers()) {
            opening.put(u.getId(), db.account(u.getId(), YEAR).getLastYearBalance());
        }

        at(LocalDateTime.of(2027, 1, 1, 1, 0, 5), () -> {
            tasks.cleanupExpiredLeaveBalances(); // 与 cron `0 0 1 1,26 1 ?` 调用的是同一个方法
        });
        Map<Long, Integer> recordsAfterFirst = recordCounts();
        Map<Long, BigDecimal> carryAfterFirst = carries();

        List<String> rows = new ArrayList<>();
        int expiredUsers = 0;
        BigDecimal expiredTotal = BigDecimal.ZERO;
        BigDecimal carryTotal = BigDecimal.ZERO;
        List<String> problems = new ArrayList<>();

        at(LocalDateTime.of(2027, 1, 1, 8, 0, 0), () -> {
            for (SysUser u : db.userMapper.selectActiveUsers()) {
                long id = u.getId();
                LeaveAccount a26 = db.account(id, YEAR);
                LeaveAccount a27 = db.account(id, YEAR + 1);
                assertTrue(a27 != null, u.getRealName() + " 没有生成 2027 账户");

                BigDecimal expired = db.sumRecords(id, "EXPIRED").negate();
                BigDecimal movements = db.allRecords(id).stream()
                        .filter(r -> !"CARRY_OVER".equals(r.getType()))
                        .filter(r -> r.getStartDate().getYear() == YEAR)
                        .map(LeaveRecord::getDays).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal shouldCarry = opening.get(id).add(a26.getActualQuota()).add(movements);
                if (shouldCarry.compareTo(a27.getLastYearBalance()) != 0) {
                    problems.add(String.format("守恒失败 %s: 期初 %s + 额度 %s + 流水 %s = %s, 但 2027 结转 %s",
                            u.getRealName(), opening.get(id), a26.getActualQuota(), movements, shouldCarry,
                            a27.getLastYearBalance()));
                }

                LeaveAccountDTO d26 = service.getAccount(id, YEAR);
                LeaveAccountDTO d27 = service.getAccount(id, YEAR + 1);
                assertInvariants(d26, u.getRealName() + "@2026");
                assertInvariants(d27, u.getRealName() + "@2027");

                rows.add(String.join("\t", String.valueOf(id), u.getEmployeeNumber(), u.getRealName(),
                        s(opening.get(id)), s(a26.getActualQuota()), s(expired), s(a27.getLastYearBalance()),
                        s(a27.getActualQuota()), s(d27.getTotalBalance()), s(d27.getCarryOverRemaining()),
                        s(d27.getCurrentQuotaRemaining()), s(d27.getFloatingDebt()),
                        s(d26.getTotalBalance()), s(d26.getCarryOverRemaining()), s(d26.getCarryOverExpired()),
                        s(d26.getFloatingDebt())));
            }
        });
        for (String row : rows) {
            String[] f = row.split("\t");
            BigDecimal e = new BigDecimal(f[5]);
            if (e.signum() > 0) {
                expiredUsers++;
                expiredTotal = expiredTotal.add(e);
            }
            carryTotal = carryTotal.add(new BigDecimal(f[6]));
        }
        write("rollover.tsv", rows);
        System.out.printf("[跨年] 作废 %d 人 共 %s 天; 结转到 2027 合计 %s 天%n", expiredUsers, expiredTotal, carryTotal);
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));

        // 1 月 26 日复跑 (cron 的第二个日期) 与随后的重复调用: 不应有任何变化
        at(LocalDateTime.of(2027, 1, 26, 1, 0, 5), () -> {
            tasks.cleanupExpiredLeaveBalances();
            tasks.cleanupExpiredLeaveBalances();
        });
        assertEquals(recordsAfterFirst, recordCounts(), "重跑不应产生新流水");
        assertEquals(carryAfterFirst, carries(), "重跑不应改变任何人的结转");
    }

    // ------------------------------------------------------------------
    // 跨年之后才同步到的 12 月请假
    // ------------------------------------------------------------------

    @Test
    @DisplayName("元旦任务之后才同步到的 12 月请假: 记到哪个桶, 员工会不会吃亏")
    void lateFiledDecemberLeave() throws Exception {
        at(LocalDateTime.of(2027, 1, 1, 1, 0, 5), () -> tasks.cleanupExpiredLeaveBalances());

        // 挑作废最多的人: 他 12 月底名下明明还有 7 天结转可用
        SysUser who = db.userMapper.selectActiveUsers().stream()
                .max(java.util.Comparator.comparing(u -> db.sumRecords(u.getId(), "EXPIRED").negate()))
                .orElseThrow();
        long id = who.getId();
        BigDecimal expiredBefore = db.sumRecords(id, "EXPIRED").negate();
        BigDecimal carry27Before = db.account(id, YEAR + 1).getLastYearBalance();
        long lastRecordId = db.allRecords(id).stream().mapToLong(LeaveRecord::getId).max().orElse(0);

        // 1 月 4 日周一的例行同步, 发现员工 12 月 29 日补请了 1 天年假
        at(LocalDateTime.of(2027, 1, 4, 2, 0, 10), () -> service.applyLeave(id, LocalDate.of(2026, 12, 29),
                LocalDate.of(2026, 12, 29), BigDecimal.ONE));
        System.out.printf("[补假] %s: 作废 %s 天, 2027 结转 %s 天; 同步到 12-29 的 1 天年假后:%n",
                who.getRealName(), expiredBefore, carry27Before);
        db.allRecords(id).stream().filter(r -> r.getId() > lastRecordId).forEach(r -> System.out.printf(
                "        新增流水 %s %-17s %5s 过期:%s %s%n", r.getStartDate(), r.getType(), r.getDays(),
                r.getExpiryDate(), r.getRemarks()));

        // 1 月 26 日复跑
        at(LocalDateTime.of(2027, 1, 26, 1, 0, 5), () -> tasks.cleanupExpiredLeaveBalances());
        BigDecimal expiredAfter = db.sumRecords(id, "EXPIRED").negate();
        BigDecimal carry27After = db.account(id, YEAR + 1).getLastYearBalance();
        System.out.printf("[补假] 1 月 26 日复跑后: 作废 %s 天 (原 %s), 2027 结转 %s 天 (原 %s)%n",
                expiredAfter, expiredBefore, carry27After, carry27Before);
        BigDecimal loss = carry27Before.subtract(carry27After);
        System.out.printf("[补假] 员工 2027 年初总额 变化 = %s 天 (12-29 那天 2026 结转本来就还没作废, 应为 0)%n",
                carry27After.subtract(carry27Before));
        // 期望行为: 12-29 那天结转仍有效, 这天的假应由结转承担 (作废 7 → 6), 2027 结转保持不变。
        // 现状: 作废流水已把结转桶清零, 补录的假被记到当年额度, 员工白白少 1 天。
        // (曾经的缺陷: 作废流水把结转桶清零, 补录的假被记到当年额度, 员工白少天数 —— 已在 deductLeaveDays 修复)
        assertEquals(0, loss.signum(), "补录 12 月请假不应减少 2027 年初的结转");
        assertEquals(0, expiredBefore.subtract(expiredAfter).compareTo(BigDecimal.ONE),
                "作废应相应减少 1 天");
    }

    // ------------------------------------------------------------------
    // 新年头几周
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新年头几周: 1 月请假与透支的显示、管理员手工更正会不会被 1 月 26 日复跑覆盖")
    void januaryScenarios() throws Exception {
        at(LocalDateTime.of(2027, 1, 1, 1, 0, 5), () -> tasks.cleanupExpiredLeaveBalances());

        List<SysUser> active = db.userMapper.selectActiveUsers();
        SysUser small = active.stream() // 结转最少的人, 用来制造透支
                .min(java.util.Comparator.comparing(u -> db.account(u.getId(), YEAR + 1).getLastYearBalance()))
                .orElseThrow();
        SysUser big = active.stream()
                .max(java.util.Comparator.comparing(u -> db.account(u.getId(), YEAR + 1).getLastYearBalance()))
                .orElseThrow();
        BigDecimal smallCarry = db.account(small.getId(), YEAR + 1).getLastYearBalance();

        at(LocalDateTime.of(2027, 1, 11, 10, 0, 0), () -> {
            service.applyLeave(big.getId(), LocalDate.of(2027, 1, 11), LocalDate.of(2027, 1, 11), BigDecimal.ONE);
            service.applyLeave(small.getId(), LocalDate.of(2027, 1, 11), LocalDate.of(2027, 1, 11),
                    smallCarry.add(new BigDecimal("0.5")));
            for (SysUser u : List.of(big, small)) {
                LeaveAccountDTO d = service.getAccount(u.getId(), YEAR + 1);
                assertInvariants(d, u.getRealName());
                System.out.printf("[1月] %s 1/11 请假后: 可休 %s = 结转 %s + 今年 %s + 透支 %s (库里结转 %s, 已累积 %s)%n",
                        u.getRealName(), d.getTotalBalance(), d.getCarryOverRemaining(),
                        d.getCurrentQuotaRemaining(), d.getFloatingDebt(), d.getLastYearBalance(),
                        d.getActualQuota());
                db.allRecords(u.getId()).stream().filter(r -> r.getStartDate().getYear() == YEAR + 1
                        && !"CARRY_OVER".equals(r.getType())).forEach(r -> System.out.printf(
                        "        %s %-17s %5s 过期:%s %s%n", r.getStartDate(), r.getType(), r.getDays(),
                        r.getExpiryDate(), r.getRemarks()));
            }
        });

        // 累积够了之后, 透支应被额度自动抵掉
        at(LocalDateTime.of(2027, 3, 20, 10, 0, 0), () -> {
            LeaveAccountDTO d = service.getAccount(small.getId(), YEAR + 1);
            assertInvariants(d, small.getRealName());
            System.out.printf("[3月] %s 3/20: 可休 %s = 结转 %s + 今年 %s + 透支 %s (已累积 %s)%n",
                    small.getRealName(), d.getTotalBalance(), d.getCarryOverRemaining(),
                    d.getCurrentQuotaRemaining(), d.getFloatingDebt(), d.getActualQuota());
        });

        // 1 月 12 日, 管理员在页面上把 big 的 2027 上年结转手工改成 99 (页面上写着「自动结转，可手工更正」)
        at(LocalDateTime.of(2027, 1, 12, 10, 0, 0), () -> {
            LeaveAccount edit = db.account(big.getId(), YEAR + 1);
            edit.setLastYearBalance(new BigDecimal("99"));
            service.updateAccount(edit);
        });
        BigDecimal manual = db.account(big.getId(), YEAR + 1).getLastYearBalance();
        at(LocalDateTime.of(2027, 1, 26, 1, 0, 5), () -> tasks.cleanupExpiredLeaveBalances());
        BigDecimal afterRerun = db.account(big.getId(), YEAR + 1).getLastYearBalance();
        System.out.printf("[更正] %s 手工改的上年结转 %s, 1 月 26 日复跑后变成 %s%n", big.getRealName(), manual, afterRerun);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** 拆分的恒等式与取值范围; 进度条画不出负长度, 三项也必须能加回余额 */
    private static void assertInvariants(LeaveAccountDTO dto, String who) {
        BigDecimal sum = dto.getCarryOverRemaining().add(dto.getCurrentQuotaRemaining()).add(dto.getFloatingDebt());
        assertEquals(0, dto.getTotalBalance().compareTo(sum), () -> String.format(
                "%s: 结转 %s + 今年 %s + 透支 %s ≠ 可休 %s", who, dto.getCarryOverRemaining(),
                dto.getCurrentQuotaRemaining(), dto.getFloatingDebt(), dto.getTotalBalance()));
        assertTrue(dto.getCarryOverRemaining().signum() >= 0, who + ": 结转剩余为负");
        assertTrue(dto.getCurrentQuotaRemaining().signum() >= 0, who + ": 今年剩余为负");
        assertTrue(dto.getFloatingDebt().signum() <= 0, who + ": 透支为正");
    }

    private Map<Long, Integer> recordCounts() {
        Map<Long, Integer> m = new LinkedHashMap<>();
        db.userMapper.selectAllUsers().forEach(u -> m.put(u.getId(), db.recordCount(u.getId())));
        return m;
    }

    private Map<Long, BigDecimal> carries() {
        Map<Long, BigDecimal> m = new LinkedHashMap<>();
        for (SysUser u : db.userMapper.selectActiveUsers()) {
            LeaveAccount a = db.account(u.getId(), YEAR + 1);
            m.put(u.getId(), a == null ? null : a.getLastYearBalance().stripTrailingZeros());
        }
        return m;
    }

    /** 把「今天」固定在某一刻; 不可嵌套 (静态 mock 是线程级的) */
    private static void at(LocalDateTime now, Body body) throws Exception {
        try (MockedStatic<LocalDate> ld = Mockito.mockStatic(LocalDate.class, Mockito.CALLS_REAL_METHODS);
                MockedStatic<LocalDateTime> ldt = Mockito.mockStatic(LocalDateTime.class,
                        Mockito.CALLS_REAL_METHODS)) {
            ld.when(() -> LocalDate.now()).thenReturn(now.toLocalDate());
            ldt.when(() -> LocalDateTime.now()).thenReturn(now);
            body.run();
        }
    }

    private static List<JsonNode> read(String name) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(SNAP, name))) {
            if (!line.isBlank()) {
                out.add(MAPPER.readTree(line));
            }
        }
        return out;
    }

    private static void write(String name, List<String> rows) throws IOException {
        Path dir = Path.of(SNAP, "out");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), rows);
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static LocalDate date(JsonNode n, String f) {
        String v = text(n, f);
        return v == null ? null : LocalDate.parse(v.substring(0, 10));
    }

    private static BigDecimal dec(JsonNode n, String f) {
        String v = text(n, f);
        return v == null ? null : new BigDecimal(v);
    }

    private static String s(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }
}
