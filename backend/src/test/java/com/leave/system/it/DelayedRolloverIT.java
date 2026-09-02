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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 年终结算延迟补跑 —— 走真实数据库与真实 SQL。
 *
 * <p>
 * 结转是「截至上年 12/31」的快照。算结转时若把目标年度已经发生的请假也扫进来,
 * 那笔假会被扣两次: 一次减进 {@code last_year_balance}, 一次算在目标年度自己的账本里,
 * 员工凭空少假。三条路径都能踩到 —— 服务器 1/1 没运行事后补跑、管理员点
 * {@code /admin/init-all-accounts} 重算、以及准点跑时编排第一步的钉钉同步窗口含 1/1 当天。
 *
 * <p>
 * 只操作自己造的 {@code __it_} 用户, 逐用户调用编排里的两个步骤, 不碰生产快照那批数据。
 * 全部跑在已过去的年度 (2023/2024) 上, 与运行日期无关。
 */
class DelayedRolloverIT extends IntegrationTestBase {

    private static final int LAST_YEAR = 2023;
    private static final int NEW_YEAR = 2024;

    @Autowired
    LeaveService leaveService;

    @Autowired
    ScheduledTasks tasks;

    @Autowired
    LeaveAccountMapper accountMapper;

    /** 编排对单个用户做的两件事: 清理上年度到期桶 + 初始化新年度账户 */
    private void rolloverFor(SysUser u) {
        LeaveAccount lastYear = accountMapper.selectAccountByUserIdAndYear(u.getId(), LAST_YEAR);
        tasks.cleanupUserForYear(lastYear, LocalDate.of(LAST_YEAR, 12, 31));
        leaveService.initYearlyAccount(u.getId(), NEW_YEAR);
    }

    private void takeLeave(SysUser u, LocalDate day, String days) {
        leaveService.applyLeave(u.getId(), day, day, new BigDecimal(days));
    }

    private BigDecimal balance(SysUser u, int year) {
        return leaveService.getAccount(u.getId(), year).getTotalBalance();
    }

    private BigDecimal carryOver(SysUser u, int year) {
        return accountMapper.selectAccountByUserIdAndYear(u.getId(), year).getLastYearBalance();
    }

    private SysUser veteran(String tag) {
        SysUser u = createTestUser(tag, 2L, LocalDate.of(2010, 1, 1), LocalDate.of(2015, 6, 1));
        leaveService.initYearlyAccount(u.getId(), LAST_YEAR);
        takeLeave(u, LocalDate.of(LAST_YEAR, 3, 1), "4.0");
        return u;
    }

    @Test
    @DisplayName("准点跑与延迟补跑, 结转值和余额必须一模一样")
    void delayedRolloverMatchesOnTimeRollover() {
        SysUser onTime = veteran("ontime");
        SysUser delayed = veteran("delayed");

        // 准点: 1/1 先结算, 之后员工才请假
        rolloverFor(onTime);
        takeLeave(onTime, LocalDate.of(NEW_YEAR, 1, 15), "2.0");

        // 延迟: 服务器 1/1 没跑, 员工 1/15 先请了假 (applyLeave 会按需建号), 事后才补跑
        takeLeave(delayed, LocalDate.of(NEW_YEAR, 1, 15), "2.0");
        BigDecimal beforeRollover = balance(delayed, NEW_YEAR);
        rolloverFor(delayed);

        assertEquals(0, beforeRollover.compareTo(balance(delayed, NEW_YEAR)),
                "补跑不应改变已经算对的余额, 补跑前 " + beforeRollover + " 补跑后 " + balance(delayed, NEW_YEAR));
        assertEquals(0, carryOver(onTime, NEW_YEAR).compareTo(carryOver(delayed, NEW_YEAR)),
                "结转值必须与结算时机无关");
        // LAST_YEAR 额度 10 - 已休 4 = 6 结转; NEW_YEAR 额度 10, 已休 2 → 14
        assertEquals(0, new BigDecimal("14.0").compareTo(balance(delayed, NEW_YEAR)));
    }

    @Test
    @DisplayName("管理员反复重算账户, 不会一次次吃掉已休的假")
    void repeatedManualReinitIsStable() {
        SysUser u = veteran("reinit");
        rolloverFor(u);
        takeLeave(u, LocalDate.of(NEW_YEAR, 5, 20), "3.0");

        BigDecimal before = balance(u, NEW_YEAR);
        for (int i = 0; i < 3; i++) {
            leaveService.initYearlyAccount(u.getId(), NEW_YEAR);
        }

        assertEquals(0, before.compareTo(balance(u, NEW_YEAR)),
                "重算 3 次后余额从 " + before + " 变成了 " + balance(u, NEW_YEAR));
    }

    @Test
    @DisplayName("补录上年度假期照样扣得到 —— 上界没有把历史流水挡在外面")
    void backfillingLastYearLeaveStillCounts() {
        SysUser u = veteran("backfill");
        rolloverFor(u);
        assertEquals(0, new BigDecimal("6.0").compareTo(carryOver(u, NEW_YEAR)));

        // NEW_YEAR 才补录一笔 LAST_YEAR 的假
        takeLeave(u, LocalDate.of(LAST_YEAR, 11, 1), "2.0");
        leaveService.initYearlyAccount(u.getId(), NEW_YEAR);

        assertEquals(0, new BigDecimal("4.0").compareTo(carryOver(u, NEW_YEAR)),
                "补录的 2 天必须从结转里扣掉, 实际 " + carryOver(u, NEW_YEAR));
    }

    @Test
    @DisplayName("过期额度还没被清理任务作废时请假, 扣的必须是新一年的额度")
    void expiredBucketIsNeverDeductedEvenBeforeCleanupRuns() {
        // 2022 年发的额度结转到 2023, 2023-12-31 到期。清理任务排在 1/25 还没跑,
        // 员工 1/10 就请了假 —— 不能扣到那笔已经过期的额度上。
        SysUser u = createTestUser("expiry", 2L, LocalDate.of(2010, 1, 1), LocalDate.of(2015, 6, 1));
        leaveService.initYearlyAccount(u.getId(), 2022);
        leaveService.initYearlyAccount(u.getId(), LAST_YEAR);

        // LAST_YEAR 两个桶: 结转 10 天 (12/31 到期) + 当年额度 10 天 (次年 12/31 到期)
        assertEquals(0, new BigDecimal("10.0").compareTo(carryOver(u, LAST_YEAR)));
        assertEquals(0, new BigDecimal("20.0").compareTo(balance(u, LAST_YEAR)));

        // 清理任务没跑, 直接在新年度 1/10 请 3 天
        takeLeave(u, LocalDate.of(NEW_YEAR, 1, 10), "3.0");

        // 结转进新年度的只有没过期的那 10 天
        assertEquals(0, new BigDecimal("10.0").compareTo(carryOver(u, NEW_YEAR)),
                "已过期的 10 天不应结转, 实际结转 " + carryOver(u, NEW_YEAR));
        // 余额 = 结转 10 + 当年额度 10 - 已休 3 = 17, 不是 20 + 10 - 3 = 27
        assertEquals(0, new BigDecimal("17.0").compareTo(balance(u, NEW_YEAR)),
                "余额应为 17.0, 实际 " + balance(u, NEW_YEAR));

        // 这 3 天必须挂在未过期的桶上 (NEW_YEAR-12-31), 不是已过期的 LAST_YEAR-12-31
        LocalDate expiry = jdbc.queryForObject(
                "SELECT expiry_date FROM leave_record WHERE user_id = ? AND type = 'ANNUAL' "
                        + "AND start_date = ? AND deleted = 0",
                LocalDate.class, u.getId(), LocalDate.of(NEW_YEAR, 1, 10));
        assertEquals(LocalDate.of(NEW_YEAR, 12, 31), expiry,
                "请假必须扣在未过期的桶上, 实际挂在 " + expiry);

        // 1/25 清理任务补跑: 补写作废流水, 余额一分不动
        rolloverFor(u);
        assertEquals(0, new BigDecimal("-10.0").compareTo(scalar(
                "SELECT COALESCE(SUM(days),0) FROM leave_record WHERE user_id = ? AND type = 'EXPIRED' "
                        + "AND deleted = 0", u.getId())),
                "应当补写 10 天的作废流水");
        assertEquals(0, new BigDecimal("17.0").compareTo(balance(u, NEW_YEAR)),
                "补写作废流水不应改变余额, 实际 " + balance(u, NEW_YEAR));
    }
}
