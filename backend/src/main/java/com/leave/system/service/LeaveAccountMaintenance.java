package com.leave.system.service;

import com.leave.system.entity.SysUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 *
 * <p>
 * <b>关键二: 动作必须等调用方的事务提交后才能跑。</b> 三个入口都由 UserService 的
 * 事务方法调用, 此时 sys_user 的写入<i>还没提交</i>; 而内层是 REQUIRES_NEW,
 * 另开一条连接, 根本读不到那行数据 ——
 * <ul>
 * <li>新增用户: initYearlyAccount 的存在性校验读不到新用户, 抛 "User not found",
 * 被下面的 catch 吞成一条 warn。用户建出来了、年假账户没有, 页面上在职天数与
 * 额度全是 0。</li>
 * <li>离职: settleYearQuota 会重新 selectUserById, 读到的是旧行 ——
 * resignation_date 还是 null, 当年额度没按离职日截断。</li>
 * </ul>
 * 所以三个入口都通过 {@link #runAfterCommit} 注册成提交后回调。此时数据已可见,
 * 而且主事务已经落库, 附带动作再失败也影响不到它 —— "尽力而为" 的语义反而更干净。
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

    /** 新增用户后初始化当年度账户, 调用方事务提交后执行, 失败仅记录日志 */
    public void initCurrentYearQuietly(SysUser user) {
        runAfterCommit(() -> {
            try {
                self.doInitCurrentYear(user);
                log.info("✅ Auto-initialized leave account for user {}", user.getUsername());
            } catch (Exception e) {
                log.warn("⚠️ Failed to auto-initialize leave account for user {}: {}",
                        user.getUsername(), e.getMessage());
            }
        });
    }

    /** 用户档案变更后刷新当年度账户, 调用方事务提交后执行, 失败仅记录日志 */
    public void refreshCurrentYearQuietly(SysUser user) {
        runAfterCommit(() -> {
            try {
                self.doRefreshCurrentYear(user);
            } catch (Exception e) {
                log.warn("⚠️ Failed to refresh leave account for user {}: {}", user.getId(), e.getMessage());
            }
        });
    }

    /** 离职结算(算定当年额度 + 清理未来年度账户), 调用方事务提交后执行, 失败仅记录日志 */
    public void settleResignationQuietly(Long userId, LocalDate resignationDate) {
        runAfterCommit(() -> {
            try {
                self.doSettleResignation(userId, resignationDate);
            } catch (Exception e) {
                log.error("⚠️ Failed to settle resignation for user {}", userId, e);
            }
        });
    }

    /**
     * 把动作推迟到当前事务提交之后执行; 没有事务在跑就地执行。
     *
     * <p>
     * 调用方事务回滚时回调不会触发 —— 用户都没建成, 自然也不该建账户。
     *
     * <p>
     * 传进来的动作必须自己吞掉异常: afterCommit 里抛出的异常会一路传播回
     * 提交处, 把"附带动作失败不影响主流程"的约定毁掉。
     */
    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
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
