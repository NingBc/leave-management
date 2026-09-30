package com.leave.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leave.system.dto.CarryOverExpiryDTO;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 余额按来源拆分 (上年结转剩余 / 今年额度剩余 / 透支) 与结转作废汇总。
 *
 * <p>
 * 与 {@link LeaveLedgerTest} 一样用过去年度 2025, 避开当年额度按天刷新。
 * 每个用例都校验一次恒等式: 结转剩余 + 今年剩余 + 透支 == 当前可休。
 */
class LeaveBalanceBreakdownTest {

    private static final int YEAR = 2025;
    private static final long USER_ID = 7L;
    private static final LocalDate CARRY_BUCKET = LocalDate.of(2025, 12, 31);
    private static final LocalDate QUOTA_BUCKET = LocalDate.of(2026, 12, 31);
    private static final LocalDate JAN_1 = LocalDate.of(YEAR, 1, 1);

    private LeaveAccountMapper accountMapper;
    private LeaveRecordMapper recordMapper;
    private SysUserMapper userMapper;
    private LeaveServiceImpl service;

    @BeforeEach
    void setUp() {
        accountMapper = mock(LeaveAccountMapper.class);
        recordMapper = mock(LeaveRecordMapper.class);
        userMapper = mock(SysUserMapper.class);
        SysJobMapper jobMapper = mock(SysJobMapper.class);
        service = new LeaveServiceImpl(accountMapper, recordMapper, userMapper, jobMapper);

        when(jobMapper.selectAllJobs()).thenReturn(Collections.emptyList());
        when(recordMapper.selectRecordsByYear(anyLong(), any())).thenReturn(Collections.emptyList());
        when(recordMapper.selectLedgerRecords(anyLong(), any(), any())).thenReturn(Collections.emptyList());
        givenUser(USER_ID);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private SysUser givenUser(long userId) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername("user" + userId);
        user.setEntryDate(LocalDate.of(2020, 1, 1));
        when(userMapper.selectUserById(userId)).thenReturn(user);
        return user;
    }

    private LeaveAccount givenAccount(long userId, String lastYearBalance, String actualQuota) {
        LeaveAccount account = new LeaveAccount();
        account.setId(userId);
        account.setUserId(userId);
        account.setYear(YEAR);
        account.setLastYearBalance(new BigDecimal(lastYearBalance));
        account.setActualQuota(new BigDecimal(actualQuota));
        account.setStandardQuota(new BigDecimal("10.0"));
        account.setDaysEmployed(365);
        account.setSocialSeniority(12);
        when(accountMapper.selectAccountByUserIdAndYear(userId, YEAR)).thenReturn(account);
        return account;
    }

    /** 账本流水; 同一批流水也作为「本年记录」返回, 与生产库里两条查询的关系一致 */
    private void givenRecords(long userId, LeaveRecord... records) {
        for (LeaveRecord r : records) {
            r.setUserId(userId);
        }
        when(recordMapper.selectLedgerRecords(eq(userId), eq(JAN_1), any())).thenReturn(List.of(records));
        when(recordMapper.selectRecordsByYear(eq(userId), eq(YEAR))).thenReturn(List.of(records));
    }

    private static LeaveRecord record(String type, String days, LocalDate expiry) {
        LeaveRecord r = new LeaveRecord();
        r.setType(type);
        r.setDays(new BigDecimal(days));
        r.setExpiryDate(expiry);
        r.setStartDate(LocalDate.of(YEAR, 6, 1));
        r.setEndDate(LocalDate.of(YEAR, 6, 1));
        return r;
    }

    private static void assertDays(String expected, BigDecimal actual, String what) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> what + ": 期望 " + expected + ", 实际 " + actual);
    }

    /** 断言拆分结果, 并校验三项之和等于余额 */
    private LeaveAccountDTO assertBreakdown(String carry, String current, String debt, String total) {
        LeaveAccountDTO dto = service.getAccount(USER_ID, YEAR);
        assertDays(carry, dto.getCarryOverRemaining(), "结转剩余");
        assertDays(current, dto.getCurrentQuotaRemaining(), "今年剩余");
        assertDays(debt, dto.getFloatingDebt(), "透支");
        assertDays(total, dto.getTotalBalance(), "当前可休");
        assertDays(total, dto.getCarryOverRemaining().add(dto.getCurrentQuotaRemaining()).add(dto.getFloatingDebt()),
                "三项之和");
        assertEquals(CARRY_BUCKET, dto.getCarryOverExpiry());
        return dto;
    }

    // ------------------------------------------------------------------
    // 拆分
    // ------------------------------------------------------------------

    @Test
    @DisplayName("结转用掉一部分: 剩余 = 结转 − 已用, 今年额度不动")
    void partiallyUsedCarryOver() {
        givenAccount(USER_ID, "10", "5");
        givenRecords(USER_ID, record("ANNUAL", "-3", CARRY_BUCKET));

        LeaveAccountDTO dto = assertBreakdown("7", "5", "0", "12");
        assertDays("0", dto.getCarryOverExpired(), "作废");
    }

    @Test
    @DisplayName("透支已归位: 那一对归位流水不影响任何一项")
    void normalizedOverdraft() {
        givenAccount(USER_ID, "2", "3.5");
        givenRecords(USER_ID,
                record("ANNUAL", "-1", CARRY_BUCKET),
                record("ANNUAL", "-1", CARRY_BUCKET),
                record("ANNUAL", "-1", null),
                record("ANNUAL", "-1", QUOTA_BUCKET),
                record("ADJUSTMENT_DEDUCT", "-1", QUOTA_BUCKET),
                record("ADJUSTMENT_ADD", "1", null),
                record("ANNUAL", "-1", QUOTA_BUCKET));

        assertBreakdown("0", "0.5", "0", "0.5");
    }

    @Test
    @DisplayName("透支还挂着但额度够: 显示为已从今年额度扣掉, 不再单列透支")
    void pendingOverdraftCoveredByQuota() {
        givenAccount(USER_ID, "0", "7");
        givenRecords(USER_ID,
                record("ANNUAL", "-0.5", QUOTA_BUCKET),
                record("ANNUAL", "-0.5", QUOTA_BUCKET),
                record("ANNUAL", "-1", null),
                record("ANNUAL", "-0.5", QUOTA_BUCKET),
                record("ANNUAL", "-0.5", null),
                record("ANNUAL", "-1", QUOTA_BUCKET));

        assertBreakdown("0", "3", "0", "3");
    }

    @Test
    @DisplayName("透支先吃结转: 结转在欠账冲抵之后才算剩余")
    void overdraftConsumesCarryOverFirst() {
        givenAccount(USER_ID, "3", "5");
        givenRecords(USER_ID, record("ANNUAL", "-1", null));

        assertBreakdown("2", "5", "0", "7");
    }

    @Test
    @DisplayName("额度抵不完: 两项剩余都是 0, 差额留在透支里")
    void overdraftExceedsQuota() {
        givenAccount(USER_ID, "0", "1");
        givenRecords(USER_ID, record("ANNUAL", "-3", null));

        assertBreakdown("0", "0", "-2", "-2");
    }

    @Test
    @DisplayName("null 桶里有没配对的正数流水: 不被欠账冲抵吞掉, 余额与冲抵前一致")
    void unpairedPositiveFloatingCredit() {
        givenAccount(USER_ID, "0", "5");
        givenRecords(USER_ID,
                record("ANNUAL", "-2", CARRY_BUCKET),      // 结转桶被超额消耗, 形成 2 天欠账
                record("ADJUSTMENT_ADD", "3", null));      // 无到期日的加假, 没有对应的透支

        // 5 (今年) − 2 (欠账) + 3 (null 桶) = 6; 欠账由今年额度冲抵, null 桶的 3 天保留
        LeaveAccountDTO dto = assertBreakdown("0", "6", "0", "6");
        assertDays("6", dto.getTotalBalance(), "冲抵不改变总额");
    }

    @Test
    @DisplayName("上年结转为负: 结转剩余记 0, 欠账从今年额度里扣掉")
    void negativeCarryOver() {
        givenAccount(USER_ID, "-2", "5");

        assertBreakdown("0", "3", "0", "3");
    }

    @Test
    @DisplayName("年终作废后: 结转剩余归零, 作废天数单独给出")
    void expiredCarryOver() {
        givenAccount(USER_ID, "5", "5");
        givenRecords(USER_ID,
                record("ANNUAL", "-2", CARRY_BUCKET),
                record("EXPIRED", "-3", CARRY_BUCKET));

        LeaveAccountDTO dto = assertBreakdown("0", "5", "0", "5");
        assertDays("3", dto.getCarryOverExpired(), "作废");
    }

    // ------------------------------------------------------------------
    // 作废汇总与「只看将作废的人」
    // ------------------------------------------------------------------

    /**
     * 1 号结转剩 7, 2 号用完, 3 号剩 2, 4 号没有账户, 5 号离职 (不在在职名单里) 剩 9。
     * 只有 1 号和 3 号应被统计, 且 1 号排前面。
     */
    private void givenTeam() {
        List<SysUser> active = new ArrayList<>();
        List<LeaveAccount> accounts = new ArrayList<>();
        for (long id = 1; id <= 4; id++) {
            active.add(givenUser(id));
        }
        givenUser(5);

        accounts.add(givenAccount(1, "10", "5"));
        givenRecords(1, record("ANNUAL", "-3", CARRY_BUCKET));
        accounts.add(givenAccount(2, "4", "5"));
        givenRecords(2, record("ANNUAL", "-4", CARRY_BUCKET));
        accounts.add(givenAccount(3, "2", "5"));
        accounts.add(givenAccount(5, "9", "5"));

        when(userMapper.selectActiveUsers()).thenReturn(active);
        when(accountMapper.selectAccountsByYear(YEAR)).thenReturn(accounts);
    }

    @Test
    @DisplayName("作废汇总: 只统计在职且结转有剩余的人")
    void carryOverExpirySummary() {
        givenTeam();

        CarryOverExpiryDTO summary = service.getCarryOverExpiry(YEAR);

        assertEquals(CARRY_BUCKET, summary.getExpiryDate());
        assertEquals(2, summary.getUserCount());
        assertDays("9", summary.getTotalDays(), "作废合计");
    }

    @Test
    @DisplayName("只看将作废的人: 剩余多的排前面, 按筛选后的人数分页")
    void expiringOnlyPage() {
        givenTeam();

        Page<LeaveAccountDTO> first = service.getAllAccountsPage(YEAR, 1, 1, true);
        Page<LeaveAccountDTO> second = service.getAllAccountsPage(YEAR, 2, 1, true);
        Page<LeaveAccountDTO> beyond = service.getAllAccountsPage(YEAR, 3, 1, true);

        assertEquals(2, first.getTotal());
        assertEquals(List.of(1L), first.getRecords().stream().map(LeaveAccountDTO::getUserId).toList());
        assertDays("7", first.getRecords().get(0).getCarryOverRemaining(), "1 号结转剩余");
        assertEquals(List.of(3L), second.getRecords().stream().map(LeaveAccountDTO::getUserId).toList());
        assertEquals(0, beyond.getRecords().size());
    }

    @Test
    @DisplayName("只看将作废的人: 分页参数越界 (size<=0、超大 size、页码<1) 不抛异常")
    void expiringOnlyPageToleratesBadPaging() {
        givenTeam();

        // size<=0 按 1 处理
        assertEquals(1, service.getAllAccountsPage(YEAR, 1, 0, true).getRecords().size());
        assertEquals(1, service.getAllAccountsPage(YEAR, 1, -5, true).getRecords().size());
        // 页码 * 超大 size 不能溢出 int
        assertEquals(0, service.getAllAccountsPage(YEAR, 3, Integer.MAX_VALUE, true).getRecords().size());
        // 页码<1 按第一页处理
        assertEquals(2, service.getAllAccountsPage(YEAR, 0, 10, true).getRecords().size());
        assertEquals(2, service.getAllAccountsPage(YEAR, -3, 10, true).getTotal());
    }
}
