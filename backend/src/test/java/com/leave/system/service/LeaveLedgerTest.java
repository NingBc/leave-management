package com.leave.system.service;

import com.leave.system.dto.LeaveAccountDTO;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 年假桶账本核心逻辑测试。
 *
 * <p>
 * 统一使用 {@code YEAR = 2025} 这个过去年度, 避开「当年账户按天动态刷新」的路径,
 * 让断言只针对分桶与分配逻辑本身。桶的语义与年份无关:
 * <ul>
 * <li>结转桶 = {@code 2025-12-31} (当年底作废)</li>
 * <li>当年额度桶 = {@code 2026-12-31} (两年有效期)</li>
 * </ul>
 */
class LeaveLedgerTest {

    private static final int YEAR = 2025;
    private static final long USER_ID = 7L;
    private static final LocalDate CARRY_BUCKET = LocalDate.of(2025, 12, 31);
    private static final LocalDate QUOTA_BUCKET = LocalDate.of(2026, 12, 31);

    private LeaveAccountMapper accountMapper;
    private LeaveRecordMapper recordMapper;
    private SysUserMapper userMapper;
    private SysJobMapper jobMapper;
    private LeaveServiceImpl service;

    /** deductLeaveDays 写入的流水 */
    private List<LeaveRecord> inserted;

    @BeforeEach
    void setUp() {
        accountMapper = mock(LeaveAccountMapper.class);
        recordMapper = mock(LeaveRecordMapper.class);
        userMapper = mock(SysUserMapper.class);
        jobMapper = mock(SysJobMapper.class);
        service = new LeaveServiceImpl(accountMapper, recordMapper, userMapper, jobMapper);

        inserted = new ArrayList<>();
        when(recordMapper.insertRecord(any(LeaveRecord.class))).thenAnswer(call -> {
            inserted.add(call.getArgument(0));
            return 1;
        });
        when(jobMapper.selectAllJobs()).thenReturn(Collections.emptyList());
        when(recordMapper.selectRecordsByYear(anyLong(), any())).thenReturn(Collections.emptyList());

        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setUsername("tester");
        user.setEntryDate(LocalDate.of(2020, 1, 1));
        when(userMapper.selectUserById(USER_ID)).thenReturn(user);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void givenAccount(String lastYearBalance, String actualQuota) {
        LeaveAccount account = new LeaveAccount();
        account.setId(1L);
        account.setUserId(USER_ID);
        account.setYear(YEAR);
        account.setLastYearBalance(new BigDecimal(lastYearBalance));
        account.setActualQuota(new BigDecimal(actualQuota));
        account.setStandardQuota(new BigDecimal("5.0"));
        account.setDaysEmployed(365);
        account.setSocialSeniority(9);
        when(accountMapper.selectAccountByUserIdAndYear(USER_ID, YEAR)).thenReturn(account);
    }

    private void givenLedgerRecords(LeaveRecord... records) {
        when(recordMapper.selectLedgerRecords(eq(USER_ID), eq(LocalDate.of(YEAR, 1, 1)), any()))
                .thenReturn(List.of(records));
    }

    private static LeaveRecord record(String type, String days, LocalDate expiry, LocalDate startDate) {
        LeaveRecord r = new LeaveRecord();
        r.setUserId(USER_ID);
        r.setType(type);
        r.setDays(new BigDecimal(days));
        r.setExpiryDate(expiry);
        r.setStartDate(startDate);
        r.setEndDate(startDate);
        r.setCreateTime(LocalDateTime.of(startDate, java.time.LocalTime.NOON));
        return r;
    }

    private BigDecimal totalBalance() {
        LeaveAccountDTO dto = service.getAccount(USER_ID, YEAR);
        return dto.getTotalBalance();
    }

    /** 按到期桶汇总本次写入的流水; expiry 为 null 的记为浮动债务 */
    private BigDecimal allocatedTo(LocalDate expiry) {
        return sumInserted(expiry, null);
    }

    /** 按到期桶 + 类型汇总本次写入的流水。扣减时会顺带写入 ADJUSTMENT_* 的透支归位流水, 需要区分开 */
    private BigDecimal sumInserted(LocalDate expiry, String type) {
        return inserted.stream()
                .filter(r -> expiry == null ? r.getExpiryDate() == null : expiry.equals(r.getExpiryDate()))
                .filter(r -> type == null || type.equals(r.getType()))
                .map(LeaveRecord::getDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 本次写入流水的净额, 即这次操作对总余额的影响 */
    private BigDecimal netInserted() {
        return inserted.stream().map(LeaveRecord::getDays).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void assertDays(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    // ------------------------------------------------------------------
    // 生产数据对齐
    // ------------------------------------------------------------------

    @Test
    @DisplayName("与生产数据一致: 结转4 + 额度3 - 已用(4+1.5) - 透支0.5 = 1.0")
    void matchesProductionShape() {
        givenAccount("4.0", "3.0");
        givenLedgerRecords(
                record("ANNUAL", "-4.0", CARRY_BUCKET, LocalDate.of(YEAR, 3, 2)),
                record("ANNUAL", "-1.5", QUOTA_BUCKET, LocalDate.of(YEAR, 5, 6)),
                record("ANNUAL", "-0.5", null, LocalDate.of(YEAR, 2, 27)));

        assertDays("1.0", totalBalance());
    }

    // ------------------------------------------------------------------
    // P0-1: 上年 ADJUSTMENT_ADD 已折进 last_year_balance, 不得再算一次
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P0-1 上年发放的额度不重复计入 (它已经在 last_year_balance 里)")
    void priorYearAdjustmentIsNotDoubleCounted() {
        // 上年发的 3 天奖励额度, 到期日落在本年结转桶; 结转值 5.0 已包含这 3 天
        givenAccount("5.0", "0.0");
        givenLedgerRecords(); // 本年度无流水; 上年的 ADJUSTMENT_ADD 不在账本查询范围内

        assertDays("5.0", totalBalance());

        service.applyLeave(USER_ID, LocalDate.of(YEAR, 6, 1), LocalDate.of(YEAR, 6, 1), new BigDecimal("6.0"));

        // 只有 5 天可用, 第 6 天必须变成透支, 而不是从"重复计入的额度"里划走
        assertDays("-5.0", allocatedTo(CARRY_BUCKET));
        assertDays("-1.0", allocatedTo(null));
    }

    // ------------------------------------------------------------------
    // P0-2: 当年 ADJUSTMENT_ADD 与当年额度同桶, 不得被覆盖
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P0-2 当年发放的额度与当年额度同桶, 必须累加而不是被覆盖")
    void sameYearAdjustmentAddsToQuotaBucket() {
        // 额度 5 + 当年奖励 3 - 已用 6 = 可用 2 (旧实现用 put 覆盖, 会算成 -1 并凭空产生透支)
        givenAccount("0.0", "5.0");
        givenLedgerRecords(
                record("ADJUSTMENT_ADD", "3.0", QUOTA_BUCKET, LocalDate.of(YEAR, 5, 1)),
                record("ANNUAL", "-6.0", QUOTA_BUCKET, LocalDate.of(YEAR, 6, 1)));

        assertDays("2.0", totalBalance());

        service.applyLeave(USER_ID, LocalDate.of(YEAR, 7, 1), LocalDate.of(YEAR, 7, 1), new BigDecimal("2.0"));

        assertDays("-2.0", allocatedTo(QUOTA_BUCKET));
        assertDays("0", allocatedTo(null)); // 不应产生任何透支
    }

    // ------------------------------------------------------------------
    // P0-3 / B: 浮动债务必须先被冲抵, 否则同一笔债被反复忽略
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P0-3 已有透支时, 可用额度必须先还债 (旧实现整个回收循环是死代码)")
    void floatingDebtIsSettledBeforeAllocation() {
        // 生产用户 10 的形状: 结转 0, 额度 6, 已用 2.5(当年桶), 透支 1.5
        // 真实可用 = 6 - 2.5 - 1.5 = 2.0; 旧实现会认为还有 3.5 可用
        givenAccount("0.0", "6.0");
        givenLedgerRecords(
                record("ANNUAL", "-2.5", QUOTA_BUCKET, LocalDate.of(YEAR, 3, 1)),
                record("ANNUAL", "-1.5", null, LocalDate.of(YEAR, 2, 14)));

        assertDays("2.0", totalBalance());

        service.applyLeave(USER_ID, LocalDate.of(YEAR, 8, 1), LocalDate.of(YEAR, 8, 1), new BigDecimal("3.0"));

        // 本次请假 3 天, 但真实可用只有 2 天
        assertDays("-2.0", sumInserted(QUOTA_BUCKET, "ANNUAL"));   // 能划走的 2 天
        assertDays("-1.0", sumInserted(null, "ANNUAL"));           // 剩下 1 天转为新的透支

        // 扣减时顺带把 1.5 天历史透支归位: 由当年额度桶承接, 同时冲销浮动债务
        assertDays("-1.5", sumInserted(QUOTA_BUCKET, "ADJUSTMENT_DEDUCT"));
        assertDays("1.5", sumInserted(null, "ADJUSTMENT_ADD"));

        // 归位不改变总余额, 所以本次写入的净额就是请的 3 天
        assertDays("-3.0", netInserted());
    }

    // ------------------------------------------------------------------
    // 分配顺序与过期
    // ------------------------------------------------------------------

    @Test
    @DisplayName("先到期先扣: 结转桶用完才动当年额度")
    void allocatesEarliestExpiringFirst() {
        givenAccount("2.0", "5.0");
        givenLedgerRecords();

        service.applyLeave(USER_ID, LocalDate.of(YEAR, 4, 1), LocalDate.of(YEAR, 4, 1), new BigDecimal("3.0"));

        assertDays("-2.0", allocatedTo(CARRY_BUCKET));
        assertDays("-1.0", allocatedTo(QUOTA_BUCKET));
        assertDays("0", allocatedTo(null));
    }

    @Test
    @DisplayName("请假日之前就已过期的桶不可用")
    void expiredBucketIsNotUsable() {
        givenAccount("0.0", "5.0");
        // 一笔到期日为上年底的额度: 本年度请假时早已作废
        givenLedgerRecords(record("ADJUSTMENT_ADD", "4.0", LocalDate.of(YEAR - 1, 12, 31), LocalDate.of(YEAR, 1, 1)));

        service.applyLeave(USER_ID, LocalDate.of(YEAR, 6, 1), LocalDate.of(YEAR, 6, 1), new BigDecimal("6.0"));

        assertDays("-5.0", allocatedTo(QUOTA_BUCKET));
        assertDays("-1.0", allocatedTo(null));
        assertTrue(inserted.stream().noneMatch(r -> LocalDate.of(YEAR - 1, 12, 31).equals(r.getExpiryDate())),
                "不应从已过期的桶里分配");
    }

    @Test
    @DisplayName("到期日晚于当年额度桶的流水属于以后年度, 不计入本年账本")
    void futureBucketIsExcluded() {
        givenAccount("0.0", "5.0");
        givenLedgerRecords(record("ADJUSTMENT_ADD", "9.0", LocalDate.of(YEAR + 2, 12, 31), LocalDate.of(YEAR + 1, 5, 1)));

        assertDays("5.0", totalBalance());
    }

    @Test
    @DisplayName("显示余额与扣减账本同源: 扣光之后余额为 0")
    void displayAndDeductionAgree() {
        givenAccount("2.0", "3.0");
        givenLedgerRecords();
        assertDays("5.0", totalBalance());

        service.applyLeave(USER_ID, LocalDate.of(YEAR, 9, 1), LocalDate.of(YEAR, 9, 1), new BigDecimal("5.0"));

        givenLedgerRecords(inserted.toArray(new LeaveRecord[0]));
        assertDays("0", totalBalance());
        assertDays("0", allocatedTo(null));
    }
}
