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
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨年结转测试。
 *
 * <p>
 * 结转的目标年度固定为 {@code 今年}, 上年度即为已关账的年度 —— 这正是
 * 「上年额度必须补算到 12/31」这条修复的适用场景。
 */
class LeaveCarryOverTest {

    private static final long USER_ID = 24L;
    private final int targetYear = LocalDate.now().getYear();
    private final int lastYear = targetYear - 1;

    private LeaveAccountMapper accountMapper;
    private LeaveRecordMapper recordMapper;
    private SysUserMapper userMapper;
    private SysJobMapper jobMapper;
    private LeaveServiceImpl service;

    private List<LeaveRecord> inserted;
    private SysUser user;

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
        when(recordMapper.selectLedgerRecords(anyLong(), any(), any())).thenReturn(Collections.emptyList());
        when(jobMapper.selectAllJobs()).thenReturn(Collections.emptyList());

        // 工龄 9 年 -> 标准额度 5 天; 入职很早 -> 上年度整年在职
        user = new SysUser();
        user.setId(USER_ID);
        user.setUsername("tester");
        user.setFirstWorkDate(LocalDate.of(2016, 12, 1));
        user.setEntryDate(LocalDate.of(2021, 6, 21));
        when(userMapper.selectUserById(USER_ID)).thenReturn(user);
    }

    private LeaveAccount lastYearAccount(String storedActualQuota, String storedLastYearBalance, int storedDaysEmployed) {
        LeaveAccount a = new LeaveAccount();
        a.setId(99L);
        a.setUserId(USER_ID);
        a.setYear(lastYear);
        a.setActualQuota(new BigDecimal(storedActualQuota));
        a.setLastYearBalance(new BigDecimal(storedLastYearBalance));
        a.setStandardQuota(new BigDecimal("5.0"));
        a.setDaysEmployed(storedDaysEmployed);
        a.setSocialSeniority(9);
        when(accountMapper.selectLastYearAccount(USER_ID, targetYear)).thenReturn(a);
        return a;
    }

    private void givenLastYearRecords(LeaveRecord... records) {
        when(recordMapper.selectLedgerRecords(eq(USER_ID), eq(LocalDate.of(lastYear, 1, 1)), any()))
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
        r.setCreateTime(LocalDateTime.of(startDate, LocalTime.NOON));
        return r;
    }

    /** 触发结转并返回写入账户的 last_year_balance */
    private BigDecimal runInitAndCaptureCarryOver() {
        service.initYearlyAccount(USER_ID, targetYear);
        ArgumentCaptor<LeaveAccount> captor = ArgumentCaptor.forClass(LeaveAccount.class);
        verify(accountMapper, atLeastOnce()).insertAccount(captor.capture());
        return captor.getValue().getLastYearBalance();
    }

    private static void assertDays(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("A: 上年额度先补算到整年, 不能拿年中冻结的残值做结转")
    void staleQuotaIsRecalculatedToFullYear() {
        // 生产现状: 夏露的 2026 账户 actual_quota 冻结在 2.0 (在职天数 177),
        // 但整年在职应为 5.0。旧实现会按 2.0 结转, 员工凭空少 3 天。
        lastYearAccount("2.0", "0.0", 177);
        givenLastYearRecords();

        assertDays("5.0", runInitAndCaptureCarryOver());
    }

    @Test
    @DisplayName("上年结转桶到期作废, 只有上年额度桶能转到今年")
    void expiredCarryOverBucketIsDropped() {
        // 上年额度 5.0 (到期 targetYear-12-31, 可转) + 上上年结转 3.0 (上年底作废)
        lastYearAccount("5.0", "3.0", 365);
        givenLastYearRecords();

        assertDays("5.0", runInitAndCaptureCarryOver());
    }

    @Test
    @DisplayName("上年用量按各自到期桶扣减")
    void lastYearUsageReducesItsOwnBucket() {
        lastYearAccount("5.0", "3.0", 365);
        givenLastYearRecords(
                // 消耗即将作废的结转桶 -> 不影响今年结转
                record("ANNUAL", "-3.0", LocalDate.of(lastYear, 12, 31), LocalDate.of(lastYear, 3, 1)),
                // 消耗上年额度桶 -> 直接减少今年结转
                record("ANNUAL", "-1.5", LocalDate.of(targetYear, 12, 31), LocalDate.of(lastYear, 9, 1)));

        assertDays("3.5", runInitAndCaptureCarryOver());
    }

    @Test
    @DisplayName("透支优先由即将作废的结转桶偿还, 不占用可转到今年的额度")
    void floatingDebtIsPaidFromTheExpiringBucketFirst() {
        // 结转桶 3.0 (今年作废) + 上年额度 5.0 (可转) - 透支 2.0
        // 债务应从最早到期的桶扣: 3.0 - 2.0 = 1.0 (随后作废), 5.0 完整结转
        lastYearAccount("5.0", "3.0", 365);
        givenLastYearRecords(record("ANNUAL", "-2.0", null, LocalDate.of(lastYear, 2, 10)));

        assertDays("5.0", runInitAndCaptureCarryOver());
    }

    @Test
    @DisplayName("还不完的透支作为负数结转到下一年")
    void unpaidDebtCarriesOverAsNegative() {
        // 无任何可用额度, 透支 2.0 -> 结转 -2.0
        lastYearAccount("0.0", "0.0", 0);
        user.setEntryDate(LocalDate.of(targetYear, 1, 1)); // 上年度未在职 -> 额度 0
        givenLastYearRecords(record("ANNUAL", "-2.0", null, LocalDate.of(lastYear, 6, 1)));

        assertDays("-2.0", runInitAndCaptureCarryOver());
    }

    @Test
    @DisplayName("上年发放的额度参与结转, 且只算一次")
    void lastYearAdjustmentAddIsCountedExactlyOnce() {
        lastYearAccount("5.0", "0.0", 365);
        givenLastYearRecords(
                record("ADJUSTMENT_ADD", "3.0", LocalDate.of(targetYear, 12, 31), LocalDate.of(lastYear, 5, 1)));

        // 5.0 额度 + 3.0 奖励 = 8.0 (旧实现在后续扣减时还会把这 3.0 再算一遍)
        assertDays("8.0", runInitAndCaptureCarryOver());
    }

    @Test
    @DisplayName("没有上年度账户时结转为 0")
    void noLastYearAccountMeansZeroCarryOver() {
        when(accountMapper.selectLastYearAccount(USER_ID, targetYear)).thenReturn(null);

        service.initYearlyAccount(USER_ID, targetYear);

        ArgumentCaptor<LeaveAccount> captor = ArgumentCaptor.forClass(LeaveAccount.class);
        verify(accountMapper, atLeastOnce()).insertAccount(captor.capture());
        assertDays("0", captor.getValue().getLastYearBalance());
    }
}
