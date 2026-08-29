package com.leave.system.it;

import com.leave.system.entity.SysUser;
import com.leave.system.service.LeaveAccountMaintenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 事务传播验证 —— 内存假库测不到的部分。
 *
 * <p>
 * 核心结论: <b>catch 的位置必须在事务边界之外</b>。只要在一个事务方法<i>内部</i>
 * 捕获来自 REQUIRED 内层方法的异常, 该事务就已经被标记成 rollback-only,
 * 提交阶段照样抛 {@link UnexpectedRollbackException} —— 而且会传播给调用方。
 * 加不加 REQUIRES_NEW 都救不了, 只是把同一个坑挪到下一层。
 */
@Import(TransactionIsolationIT.Beans.class)
class TransactionIsolationIT extends IntegrationTestBase {

    @Autowired
    LeaveAccountMaintenance maintenance;

    @Autowired
    TransactionTemplate txTemplate;

    @Autowired
    CatchInsideRequired catchInsideRequired;

    @Autowired
    CatchInsideRequiresNew catchInsideRequiresNew;

    @Autowired
    CatchOutside catchOutside;

    private long countUsers(String username) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username = ?", Long.class, username);
    }

    private String marker(String tag) {
        return IT_PREFIX + tag + "_" + System.nanoTime();
    }

    @Test
    @DisplayName("反面教材一: 在 REQUIRED 事务里吞掉内层异常 → 整批回滚")
    void catchInsideRequiredRollsBackEverything() {
        String username = marker("req");

        assertThrows(UnexpectedRollbackException.class,
                () -> catchInsideRequired.run(username),
                "这是修复前 addUser / updateUser / resignUser / cleanupExpiredLeaveBalances 的形状");

        assertEquals(0, countUsers(username), "日志会显示成功, 但数据全部回滚了");
    }

    @Test
    @DisplayName("反面教材二: 换成 REQUIRES_NEW 但 catch 仍在事务内 → 依旧回滚, 还会污染调用方")
    void catchInsideRequiresNewStillRollsBack() {
        String username = marker("reqnew");

        assertThrows(UnexpectedRollbackException.class,
                () -> catchInsideRequiresNew.run(username),
                "REQUIRES_NEW 只是把同一个坑挪到下一层 —— 这正是 LeaveAccountMaintenance 最初的写法");

        assertEquals(0, countUsers(username));
    }

    @Test
    @DisplayName("正确写法: 事务方法不吞异常, catch 放在事务边界之外")
    void catchOutsideTransactionIsolatesFailure() {
        String username = marker("ok");

        assertDoesNotThrow(() -> catchOutside.run(username));

        assertEquals(1, countUsers(username), "外层的写入应当正常提交");
        jdbc.update("DELETE FROM sys_user WHERE username = ?", username);
    }

    @Test
    @DisplayName("LeaveAccountMaintenance 三个入口都不会把调用方事务拖下水")
    void maintenanceEntryPointsAreIsolated() {
        // 不存在的用户, 三个维护动作内部必然失败
        SysUser ghost = new SysUser();
        ghost.setId(-999L);
        ghost.setUsername("__it_ghost");

        String marker = marker("maint");
        assertDoesNotThrow(() -> txTemplate.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO sys_user (username, password, real_name, role_id, status, create_time, deleted) "
                    + "VALUES (?,'x','marker',2,'ACTIVE',NOW(),0)", marker);
            maintenance.initCurrentYearQuietly(ghost);
            maintenance.refreshCurrentYearQuietly(ghost);
            maintenance.settleResignationQuietly(-999L, LocalDate.now());
        }), "维护动作失败不应让调用方的事务提交失败");

        assertEquals(1, countUsers(marker), "调用方的写入必须留下来");
        jdbc.update("DELETE FROM sys_user WHERE username = ?", marker);
    }

    @Test
    @DisplayName("离职结算成功路径: 保留当年账户, 清掉之后年度")
    void resignationSettlementCommits() {
        SysUser user = createTestUser("resign", 2L, LocalDate.of(2012, 1, 1), LocalDate.of(2016, 5, 1));
        int year = LocalDate.now().getYear();

        // 当年 + 次年各一个账户: settleResignation 只结算已存在的账户, 不会凭空建号
        for (int y : new int[] { year, year + 1 }) {
            jdbc.update("INSERT INTO leave_account (user_id, year, social_seniority, standard_quota, days_employed, "
                    + "actual_quota, last_year_balance, deleted) VALUES (?,?,?,?,?,?,?,0)",
                    user.getId(), y, 10, 10.0, 365, 10.0, 0.0);
        }

        maintenance.settleResignationQuietly(user.getId(), LocalDate.now());

        Long current = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_account WHERE user_id = ? AND year = ? AND deleted = 0",
                Long.class, user.getId(), year);
        Long future = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_account WHERE user_id = ? AND year = ? AND deleted = 0",
                Long.class, user.getId(), year + 1);

        assertEquals(1L, current, "离职当年的账户必须保留, 否则没有结算依据");
        assertEquals(0L, future, "离职年度之后的账户应当清理");
    }

    // ------------------------------------------------------------------
    // 三种写法的最小复现。必须走 @TestConfiguration 显式声明 ——
    // @SpringBootTest 会用 TypeExcludeFilter 把测试类排除在组件扫描之外。
    // ------------------------------------------------------------------

    @TestConfiguration
    static class Beans {
        @Bean
        FailingInner failingInner() {
            return new FailingInner();
        }

        @Bean
        CatchInsideRequired catchInsideRequired(FailingInner inner, JdbcTemplate jdbc) {
            return new CatchInsideRequired(inner, jdbc);
        }

        @Bean
        CatchInsideRequiresNew catchInsideRequiresNew(FailingInner inner, JdbcTemplate jdbc) {
            return new CatchInsideRequiresNew(inner, jdbc);
        }

        @Bean
        IsolatedWork isolatedWork(FailingInner inner) {
            return new IsolatedWork(inner);
        }

        @Bean
        CatchOutside catchOutside(IsolatedWork work, JdbcTemplate jdbc) {
            return new CatchOutside(work, jdbc);
        }
    }

    /** 模拟 LeaveService: REQUIRED 传播, 会抛业务异常 */
    static class FailingInner {
        @Transactional(rollbackFor = Exception.class)
        public void fail() {
            throw new IllegalStateException("inner failed");
        }
    }

    abstract static class Pattern {
        final FailingInner inner;
        final JdbcTemplate jdbc;

        Pattern(FailingInner inner, JdbcTemplate jdbc) {
            this.inner = inner;
            this.jdbc = jdbc;
        }

        void insert(String username, String realName) {
            jdbc.update("INSERT INTO sys_user (username, password, real_name, role_id, status, create_time, deleted) "
                    + "VALUES (?,'x',?,2,'ACTIVE',NOW(),0)", username, realName);
        }
    }

    static class CatchInsideRequired extends Pattern {
        CatchInsideRequired(FailingInner inner, JdbcTemplate jdbc) {
            super(inner, jdbc);
        }

        @Transactional(rollbackFor = Exception.class)
        public void run(String username) {
            insert(username, "buggy-required");
            try {
                inner.fail();
            } catch (Exception ignored) {
                // 以为 catch 住就没事了
            }
        }
    }

    static class CatchInsideRequiresNew extends Pattern {
        CatchInsideRequiresNew(FailingInner inner, JdbcTemplate jdbc) {
            super(inner, jdbc);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
        public void run(String username) {
            insert(username, "buggy-requires-new");
            try {
                inner.fail();
            } catch (Exception ignored) {
                // 换成 REQUIRES_NEW 也没用: 本事务同样被标记为 rollback-only
            }
        }
    }

    /**
     * 正确写法: 捕获与事务拆成两个 bean。
     *
     * <p>
     * 拆成两个而不是同类的两个方法, 是为了避免自调用绕过代理 —— {@code this.isolated()}
     * 根本不会开启 REQUIRES_NEW, 那样测试即使通过也是假通过。真实代码里
     * {@code LeaveAccountMaintenance} 用 {@code @Lazy} 自注入代理达到同样效果。
     */
    static class CatchOutside {
        private final IsolatedWork work;
        private final JdbcTemplate jdbc;

        CatchOutside(IsolatedWork work, JdbcTemplate jdbc) {
            this.work = work;
            this.jdbc = jdbc;
        }

        /** 无事务, 只负责捕获 */
        public void run(String username) {
            jdbc.update("INSERT INTO sys_user (username, password, real_name, role_id, status, create_time, deleted) "
                    + "VALUES (?,'x','fixed',2,'ACTIVE',NOW(),0)", username);
            try {
                work.isolated();
            } catch (Exception ignored) {
                // catch 在事务边界之外, 内层事务已经干净地回滚完毕
            }
        }
    }

    static class IsolatedWork {
        private final FailingInner inner;

        IsolatedWork(FailingInner inner) {
            this.inner = inner;
        }

        /** 独立事务, 不吞异常 —— 让它干净地回滚并把异常抛出去 */
        @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
        public void isolated() {
            inner.fail();
        }
    }
}
