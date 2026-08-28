package com.leave.system.service;

import com.leave.system.entity.SysUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 「尽力而为」的年假账户维护入口。
 *
 * <p>
 * 用户新增/修改/离职时顺带维护年假账户属于附带动作, 失败不应该让主流程失败。
 * 但如果直接在主事务里调用 {@link LeaveService} 的 {@code @Transactional} 方法再 catch 掉异常,
 * 共享事务已被标记 rollback-only, 主流程提交时会抛 UnexpectedRollbackException ——
 * 表面「已捕获」, 实际整个操作被回滚。
 *
 * <p>
 * 因此这里统一用 {@link Propagation#REQUIRES_NEW} 开独立事务, 真正做到失败隔离。
 */
@Component
public class LeaveAccountMaintenance {

    private static final Logger log = LoggerFactory.getLogger(LeaveAccountMaintenance.class);

    private final LeaveService leaveService;

    public LeaveAccountMaintenance(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    /** 新增用户后初始化当年度账户, 失败仅记录日志 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void initCurrentYearQuietly(SysUser user) {
        try {
            leaveService.initYearlyAccount(user.getId(), LocalDate.now().getYear());
            log.info("✅ Auto-initialized leave account for user {}", user.getUsername());
        } catch (Exception e) {
            log.warn("⚠️ Failed to auto-initialize leave account for user {}: {}", user.getUsername(), e.getMessage());
        }
    }

    /** 用户档案变更后刷新当年度账户, 失败仅记录日志 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void refreshCurrentYearQuietly(SysUser user) {
        try {
            leaveService.refreshAccount(user, LocalDate.now().getYear());
        } catch (Exception e) {
            log.warn("⚠️ Failed to refresh leave account for user {}: {}", user.getId(), e.getMessage());
        }
    }

    /** 离职结算(算定当年额度 + 清理未来年度账户), 失败仅记录日志 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void settleResignationQuietly(Long userId, LocalDate resignationDate) {
        try {
            leaveService.settleResignation(userId, resignationDate);
        } catch (Exception e) {
            log.error("⚠️ Failed to settle resignation for user {}", userId, e);
        }
    }
}
