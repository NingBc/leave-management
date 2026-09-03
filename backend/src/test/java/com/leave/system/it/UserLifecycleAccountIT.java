package com.leave.system.it;

import com.leave.system.entity.SysUser;
import com.leave.system.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 用户生命周期动作对年假账户的连带影响 —— 必须跑在真实事务管理器上。
 *
 * <p>
 * 这两个用例守的是同一条铁律: {@code LeaveAccountMaintenance} 的动作要等
 * 调用方事务提交后再跑。修复前它们是这样挂的:
 * <ul>
 * <li>新增: REQUIRES_NEW 读不到未提交的新用户, 抛 "User not found" 被吞,
 * 账户压根没建出来, 页面在职天数显示 0</li>
 * <li>离职: settleYearQuota 重新查库读到旧行, resignation_date 还是 null,
 * 当年额度没按离职日截断</li>
 * </ul>
 */
class UserLifecycleAccountIT extends IntegrationTestBase {

    @Autowired
    UserService userService;

    private SysUser addUser(String tag, LocalDate entryDate) {
        SysUser user = new SysUser();
        user.setUsername(IT_PREFIX + tag + "_" + System.nanoTime());
        user.setPassword("123456");
        user.setRealName("IT-" + tag);
        user.setRoleId(2L);
        user.setStatus("ACTIVE");
        user.setEntryDate(entryDate);
        user.setFirstWorkDate(LocalDate.of(2015, 1, 1));

        userService.addUser(user);

        SysUser saved = userMapper.selectByUsername(user.getUsername());
        assertNotNull(saved, "用户本身应当创建成功");
        return saved;
    }

    private Integer daysEmployed(Long userId, int year) {
        return jdbc.queryForObject(
                "SELECT days_employed FROM leave_account WHERE user_id = ? AND year = ? AND deleted = 0",
                Integer.class, userId, year);
    }

    @Test
    @DisplayName("新增员工后自动建出当年账户, 在职天数按入职日算到今天")
    void addUserCreatesAccountWithDaysEmployed() {
        int year = LocalDate.now().getYear();
        LocalDate entryDate = LocalDate.of(year, 1, 1);

        SysUser saved = addUser("adduser", entryDate);

        Long accountCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_account WHERE user_id = ? AND year = ? AND deleted = 0",
                Long.class, saved.getId(), year);
        assertEquals(1L, accountCount, "新增员工后必须自动建出当年年假账户");

        int expected = (int) ChronoUnit.DAYS.between(entryDate, LocalDate.now()) + 1;
        assertEquals(expected, daysEmployed(saved.getId(), year), "在职天数应当是入职日到今天");
    }

    @Test
    @DisplayName("离职后当年在职天数截断到离职日")
    void resignTruncatesDaysEmployedToResignationDate() {
        int year = LocalDate.now().getYear();
        LocalDate entryDate = LocalDate.of(year, 1, 1);
        // 离职日取上个月月末, 确保早于今天 —— 否则截不截断看不出区别
        LocalDate resignationDate = LocalDate.now().withDayOfMonth(1).minusDays(1);

        // 刻意不走 addUser: 这个用例只守离职截断这一件事,
        // 账户先备好, 新增路径坏没坏都不影响它的判定。
        SysUser saved = createTestUser("resign", 2L, LocalDate.of(2015, 1, 1), entryDate);
        int daysBeforeResign = (int) ChronoUnit.DAYS.between(entryDate, LocalDate.now()) + 1;
        jdbc.update("INSERT INTO leave_account (user_id, year, social_seniority, standard_quota, "
                + "days_employed, actual_quota, last_year_balance, deleted) VALUES (?,?,?,?,?,?,?,0)",
                saved.getId(), year, 10, 10.0, daysBeforeResign, 6.0, 0.0);

        Map<String, String> body = new HashMap<>();
        body.put("resignationDate", resignationDate.toString());
        userService.resignUser(saved.getId(), body);

        int expected = (int) ChronoUnit.DAYS.between(entryDate, resignationDate) + 1;
        assertEquals(expected, daysEmployed(saved.getId(), year),
                "离职当年的在职天数应当截到离职日 " + resignationDate);
    }
}
