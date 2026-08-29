package com.leave.system.service;

import com.leave.system.entity.SysUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 「尽力而为」的年假账户维护入口。
 *
 * <p>
 * 用户新增/修改/离职时顺带维护年假账户属于附带动作, 失败不应该让主流程失败。
 *
 * <p>
 * <b>关键: try/catch 必须在事务边界之外。</b> 每个入口拆成两个方法 ——
 * 外层 {@code xxxQuietly} 不带事务, 只负责捕获日志; 内层 {@code doXxx} 是
 * {@link Propagation#REQUIRES_NEW} 的独立事务, 不吞异常。
 *
 * <p>
 * 如果把 catch 写在 REQUIRES_NEW 方法<i>内部</i>(最初的写法), 内层
 * {@code LeaveService} 方法(REQUIRED 传播)抛出的异常已经把这个新事务标记成
 * rollback-only, 捕获之后 Spring 仍然会在提交阶段抛 UnexpectedRollbackException,
 * 并且一路传播回调用方, 把调用方的事务也拖下水 —— 换了一层皮的同一个 bug。
 * 这个坑只有真实事务管理器能复现, 见 TransactionIsolationIT。
 */
@Component
public class LeaveAccountMaintenance {

    private static final Logger log = LoggerFactory.getLogger(LeaveAccountMaintenance.class);

    private final LeaveService leaveService;

    /** 自身代理引用: REQUIRES_NEW 靠 Spring 代理生效, this.xxx() 会绕过代理 */
    @Autowired
    @Lazy
    private LeaveAccountMaintenance self;

    public LeaveAccountMaintenance(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    // ------------------------------------------------------------------
    // 外层: 无事务, 只捕获日志
    // ------------------------------------------------------------------

    /** 新增用户后初始化当年度账户, 失败仅记录日志 */
    public void initCurrentYearQuietly(SysUser user) {
        try {
            self.doInitCurrentYear(user);
            log.info("✅ Auto-initialized leave account for user {}", user.getUsername());
        } catch (Exception e) {
            log.warn("⚠️ Failed to auto-initialize leave account for user {}: {}", user.getUsername(), e.getMessage());
        }
    }

    /** 用户档案变更后刷新当年度账户, 失败仅记录日志 */
    public void refreshCurrentYearQuietly(SysUser user) {
        try {
            self.doRefreshCurrentYear(user);
        } catch (Exception e) {
            log.warn("⚠️ Failed to refresh leave account for user {}: {}", user.getId(), e.getMessage());
        }
    }

    /** 离职结算(算定当年额度 + 清理未来年度账户), 失败仅记录日志 */
    public void settleResignationQuietly(Long userId, LocalDate resignationDate) {
        try {
            self.doSettleResignation(userId, resignationDate);
        } catch (Exception e) {
            log.error("⚠️ Failed to settle resignation for user {}", userId, e);
        }
    }

    // ------------------------------------------------------------------
    // 内层: 独立事务, 不吞异常
    // ------------------------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void doInitCurrentYear(SysUser user) {
        leaveService.initYearlyAccount(user.getId(), LocalDate.now().getYear());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void doRefreshCurrentYear(SysUser user) {
        leaveService.refreshAccount(user, LocalDate.now().getYear());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void doSettleResignation(Long userId, LocalDate resignationDate) {
        leaveService.settleResignation(userId, resignationDate);
    }
}
