package com.leave.system.it;

import com.leave.system.entity.SysUser;
import com.leave.system.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试基类: 真实 Spring 上下文 + 真实 MySQL。
 *
 * <p>
 * 这类测试补的是内存假库测不到的部分 —— 真实 SQL 的执行、事务传播、Web 层鉴权。
 * 它们<b>会写数据库</b>, 所以:
 * <ul>
 * <li>只连测试库 {@code leave_system_test}, 并在每次运行前断言 URL 不是生产库</li>
 * <li>只操作自己造的测试用户 (用户名以 {@code __it_} 开头), 跑完即清理,
 * 不碰从生产复制过来的那批数据</li>
 * </ul>
 *
 * <p>
 * 类名以 {@code IT} 结尾, surefire 默认不会执行。手工运行:
 * <pre>
 *   mvn -o test -Dtest='*IT'
 * </pre>
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@ActiveProfiles("integration")
public abstract class IntegrationTestBase {

    static {
        // macOS 若开了系统级 SOCKS 代理(Clash 等), JVM 会自动把它当作 socksProxyHost,
        // 于是所有 Socket 都被送去代理, 而代理不转发内网地址 —— 表现为 TCP 连得上、
        // 却读不到 MySQL 握手包 (Communications link failure)。
        // mysql/curl/python 不读这个设置, 所以只有 JVM 受影响, 很容易误判成驱动或 SSL 问题。
        String existing = System.getProperty("socksNonProxyHosts", "");
        String bypass = "10.*|192.168.*|localhost|127.0.0.1";
        System.setProperty("socksNonProxyHosts", existing.isEmpty() ? bypass : existing + "|" + bypass);
    }

    /** 测试用户的用户名前缀, 清理时据此识别 */
    protected static final String IT_PREFIX = "__it_";

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 100000);

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected SysUserMapper userMapper;

    @BeforeEach
    void guardAndClean() {
        assertNotProduction();
        cleanupTestUsers();
    }

    /** 跑完也清一次, 免得最后一个用例的数据留在测试库里 */
    @AfterEach
    void cleanAfter() {
        cleanupTestUsers();
    }

    /** 最后一道闸: 连错库就直接失败, 绝不让集成测试写到生产 */
    private void assertNotProduction() {
        String url = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c -> c.getMetaData()
                .getURL());
        assertTrue(url.contains("leave_system_test"),
                "集成测试只允许连测试库, 当前 URL: " + url);
    }

    /** 清掉上一轮遗留的测试数据 (只删本类造的用户及其年假数据) */
    protected void cleanupTestUsers() {
        jdbc.update("DELETE r FROM leave_record r JOIN sys_user u ON u.id = r.user_id "
                + "WHERE u.username LIKE ?", IT_PREFIX + "%");
        jdbc.update("DELETE a FROM leave_account a JOIN sys_user u ON u.id = a.user_id "
                + "WHERE u.username LIKE ?", IT_PREFIX + "%");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE ?", IT_PREFIX + "%");
    }

    /**
     * 造一个测试员工。
     *
     * @param roleId 1=管理员, 2=普通员工
     */
    protected SysUser createTestUser(String tag, Long roleId, LocalDate firstWorkDate, LocalDate entryDate) {
        String username = IT_PREFIX + tag + "_" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO sys_user (username, password, real_name, role_id, status, "
                + "first_work_date, entry_date, create_time, deleted) VALUES (?,?,?,?,?,?,?,?,0)",
                username,
                // BCrypt("123456"), 与 schema.sql 里的 admin 一致
                "$2b$12$sDUO0pQ/tZBzMgNQWqVhwujpMkIWLuV97C/Gmb2Fi7GqoWQcXqXlu",
                "IT-" + tag, roleId, "ACTIVE",
                firstWorkDate, entryDate, LocalDateTime.now());
        return userMapper.selectByUsername(username);
    }

    protected java.math.BigDecimal scalar(String sql, Object... args) {
        java.math.BigDecimal v = jdbc.queryForObject(sql, java.math.BigDecimal.class, args);
        return v == null ? java.math.BigDecimal.ZERO : v;
    }
}
