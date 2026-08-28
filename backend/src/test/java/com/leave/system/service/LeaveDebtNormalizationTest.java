package com.leave.system.service;

import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.LeaveAccountMapper;
import com.leave.system.mapper.LeaveRecordMapper;
import com.leave.system.mapper.SysJobMapper;
import com.leave.system.mapper.SysUserMapper;
import com.leave.system.service.impl.LeaveServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 历史透支归位测试。
 *
 * <p>
 * 场景取自生产用户「贾玉龙」: 按天累计的额度模型下他在 2 月请假产生了 1.5 天透支,
 * 到 8 月额度已累计到 6.5 天, 那笔透支早就不成立, 却仍以「额度透支」挂在账上。
 *
 * <p>
 * 归位有两个触发点, 这里都覆盖:
 * <ul>
 * <li>{@code settleYearQuota} —— 年终结算 / 离职结算</li>
 * <li>{@code applyLeave} —— 每次扣减时账户自愈</li>
 * </ul>
 */
class LeaveDebtNormalizationTest {

    private static final long USER_ID = 10L;
    private final int year = LocalDate.now().getYear();
    private final LocalDate carryBucket = LocalDate.of(year, 12, 31);
    private final LocalDate quotaBucket = LocalDate.of(year + 1, 12, 31);

    private LeaveAccountMapper accountMapper;
    private LeaveRecordMapper recordMapper;
    private SysUserMapper userMapper;
    private LeaveServiceImpl service;

    private List<LeaveRecord> inserted;
    private List<LeaveRecord> ledger;

    @BeforeEach
    void setUp() {
        accountMapper = mock(LeaveAccountMapper.class);
        recordMapper = mock(LeaveRecordMapper.class);
        userMapper = mock(SysUserMapper.class);
        SysJobMapper jobMapper = mock(SysJobMapper.class);
        service = new LeaveServiceImpl(accountMapper, recordMapper, userMapper, jobMapper);

        inserted = new ArrayList<>();
        ledger = new ArrayList<>();
        when(recordMapper.insertRecord(any(LeaveRecord.class))).thenAnswer(call -> {
            LeaveRecord r = call.getArgument(0);
            inserted.add(r);
            ledger.add(r); // 写入后立刻对账本可见, 便于验证幂等
            return 1;
        });
        when(jobMapper.selectAllJobs()).thenReturn(Collections.emptyList());
        when(recordMapper.selectLedgerRecords(eq(USER_ID), eq(LocalDate.of(year, 1, 1))))
                .thenAnswer(call -> new ArrayList<>(ledger));
        when(recordMapper.selectRecordsByYear(anyLong(), any())).thenReturn(Collections.emptyList());

        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setUsername("jiayulong");
        user.setFirstWorkDate(LocalDate.of(2010, 9, 1));
        user.setEntryDate(LocalDate.of(2020, 8, 24));
        when(userMapper.selectUserById(USER_ID)).thenReturn(user);
    }

    private LeaveAccount givenAccount(String lastYearBalance, String actualQuota) {
        LeaveAccount a = new LeaveAccount();
        a.setId(7L);
        a.setUserId(USER_ID);
        a.setYear(year);
        a.setLastYearBalance(new BigDecimal(lastYearBalance));
        a.setActualQuota(new BigDecimal(actualQuota));
        a.setStandardQuota(new BigDecimal("10.0"));
        a.setDaysEmployed(240);
        a.setSocialSeniority(15);
        when(accountMapper.selectAccountByUserIdAndYear(USER_ID, year)).thenReturn(a);
        return a;
    }

    private LeaveRecord record(String type, String days, LocalDate expiry, LocalDate startDate) {
        LeaveRecord r = new LeaveRecord();
        r.setUserId(USER_ID);
        r.setType(type);
        r.setDays(new BigDecimal(days));
        r.setExpiryDate(expiry);
        r.setStartDate(startDate);
        r.setEndDate(startDate);
        r.setCreateTime(LocalDateTime.of(startDate, LocalTime.NOON));
        return r;
    }

    private BigDecimal sumOf(LocalDate expiry) {
        return inserted.stream()
                .filter(r -> expiry == null ? r.getExpiryDate() == null : expiry.equals(r.getExpiryDate()))
                .map(LeaveRecord::getDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void assertDays(String expected, BigDecimal actual) {
        assertDays(new BigDecimal(expected), actual);
    }

    private static void assertDays(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    /**
     * 重算之后的当年额度。
     * refreshQuotaAndSettleDebt 会先按「标准额度 × 在职天数 / 全年天数」重算 actual_quota,
     * 所以断言必须以重算结果为基准, 否则测试会随日期推移而失败。
     */
    private BigDecimal recalculatedQuota() {
        return service.getAccount(USER_ID, year).getActualQuota();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("额度足以覆盖时, 历史透支被归位, 总余额不变")
    void normalizesDebtWhenQuotaCoversIt() {
        givenAccount("0.0", "6.5");
        ledger.add(record("ANNUAL", "-2.5", quotaBucket, LocalDate.of(year, 5, 29)));
        ledger.add(record("ANNUAL", "-1.5", null, LocalDate.of(year, 2, 14)));

        BigDecimal quota = recalculatedQuota();
        BigDecimal before = service.getAccount(USER_ID, year).getTotalBalance();
        assertDays(quota.subtract(new BigDecimal("4.0")), before);

        assertTrue(service.settleYearQuota(USER_ID, year));

        // 一对冲抵流水: 从当年额度桶扣 1.5, 同时冲销 1.5 浮动债务
        assertDays("-1.5", sumOf(quotaBucket));
        assertDays("1.5", sumOf(null));

        // 余额分文不动
        assertDays(before, service.getAccount(USER_ID, year).getTotalBalance());
    }

    @Test
    @DisplayName("归位后浮动债务清零, 重复执行不再产生流水 (幂等)")
    void isIdempotent() {
        givenAccount("0.0", "6.5");
        ledger.add(record("ANNUAL", "-1.5", null, LocalDate.of(year, 2, 14)));

        assertTrue(service.settleYearQuota(USER_ID, year));
        int afterFirst = inserted.size();

        assertFalse(service.settleYearQuota(USER_ID, year));
        assertEquals(afterFirst, inserted.size(), "第二次执行不应再写入任何流水");
    }

    @Test
    @DisplayName("额度不足时只归位能覆盖的部分, 剩余仍为透支")
    void settlesOnlyWhatIsCovered() {
        givenAccount("0.0", "0.0");
        // 债务远大于标准额度上限(10 天), 保证一定覆盖不完
        BigDecimal debt = new BigDecimal("20.0");
        ledger.add(record("ANNUAL", debt.negate().toPlainString(), null, LocalDate.of(year, 2, 14)));

        BigDecimal quota = recalculatedQuota();
        assertTrue(service.settleYearQuota(USER_ID, year));

        // 只能归位到当年额度为止
        assertDays(quota.negate(), sumOf(quotaBucket));
        assertDays(quota, sumOf(null));
        // 余额仍为负: 额度 - 债务
        assertDays(quota.subtract(debt), service.getAccount(USER_ID, year).getTotalBalance());
    }

    @Test
    @DisplayName("优先消耗最早到期的桶: 结转桶先于当年额度桶")
    void consumesEarliestExpiringBucketFirst() {
        givenAccount("1.0", "6.5");
        ledger.add(record("ANNUAL", "-2.0", null, LocalDate.of(year, 2, 14)));

        assertTrue(service.settleYearQuota(USER_ID, year));

        assertDays("-1.0", sumOf(carryBucket));   // 结转桶 1.0 先被用掉
        assertDays("-1.0", sumOf(quotaBucket));   // 余下 1.0 由当年额度承接
        assertDays("2.0", sumOf(null));
    }

    @Test
    @DisplayName("没有透支时什么都不做")
    void doesNothingWithoutDebt() {
        givenAccount("0.0", "6.5");
        ledger.add(record("ANNUAL", "-2.5", quotaBucket, LocalDate.of(year, 5, 29)));

        service.settleYearQuota(USER_ID, year);

        assertTrue(inserted.isEmpty(), "无透支时不应写入任何流水");
    }

    @Test
    @DisplayName("扣减路径同样会归位: 请假时账户自愈")
    void deductionPathNormalizesToo() {
        givenAccount("0.0", "6.5");
        ledger.add(record("ANNUAL", "-1.5", null, LocalDate.of(year, 2, 14)));

        BigDecimal quota = recalculatedQuota();
        service.applyLeave(USER_ID, LocalDate.of(year, 8, 1), LocalDate.of(year, 8, 1), new BigDecimal("1.0"));

        // 归位的 1.5 + 本次请假的 1.0 都记在当年额度桶上
        assertDays("-2.5", sumOf(quotaBucket));
        // 浮动债务被冲销, 没有产生新的透支
        assertDays("1.5", sumOf(null));
        assertDays(quota.subtract(new BigDecimal("2.5")), service.getAccount(USER_ID, year).getTotalBalance());
    }
}
