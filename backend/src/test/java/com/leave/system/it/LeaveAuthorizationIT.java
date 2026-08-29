package com.leave.system.it;

import com.leave.system.entity.SysUser;
import com.leave.system.security.JwtUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 年假接口鉴权验证 —— 走完整的 HTTP 链路 (JWT 过滤器 → SecurityFilterChain → @PreAuthorize)。
 *
 * <p>
 * 修复前这里有两个叠加的问题:
 * <ul>
 * <li>{@code UserDetailsServiceImpl} 返回空权限集合, 所有 {@code hasRole('ADMIN')}
 * 恒为拒绝 —— 管理员接口对管理员自己也是 403</li>
 * <li>{@code LeaveController} 全类没有任何鉴权, 任何登录用户都能查看全公司年假、
 * 修改他人结余、给自己新增额度</li>
 * </ul>
 */
@AutoConfigureMockMvc
class LeaveAuthorizationIT extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtUtils jwtUtils;

    @Autowired
    UserDetailsService userDetailsService;

    private SysUser employee;
    private SysUser otherEmployee;
    private SysUser admin;

    private SysUser employee() {
        if (employee == null) {
            employee = createTestUser("emp", 2L, LocalDate.of(2015, 1, 1), LocalDate.of(2018, 3, 1));
        }
        return employee;
    }

    private SysUser otherEmployee() {
        if (otherEmployee == null) {
            otherEmployee = createTestUser("other", 2L, LocalDate.of(2015, 1, 1), LocalDate.of(2018, 3, 1));
        }
        return otherEmployee;
    }

    private SysUser admin() {
        if (admin == null) {
            admin = createTestUser("admin", 1L, LocalDate.of(2010, 1, 1), LocalDate.of(2015, 1, 1));
        }
        return admin;
    }

    /** 按真实登录流程签发 token */
    private String tokenFor(SysUser user) {
        UserDetails details = userDetailsService.loadUserByUsername(user.getUsername());
        return jwtUtils.generateToken(details);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, SysUser user) {
        return builder.header("Authorization", "Bearer " + tokenFor(user));
    }

    // ------------------------------------------------------------------
    // 权限确实被加载了 (这是所有 @PreAuthorize 生效的前提)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UserDetailsService 会把角色加载成 ROLE_ADMIN / ROLE_USER")
    void authoritiesAreLoadedFromRole() {
        UserDetails adminDetails = userDetailsService.loadUserByUsername(admin().getUsername());
        UserDetails empDetails = userDetailsService.loadUserByUsername(employee().getUsername());

        assertTrue(adminDetails.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())),
                "管理员必须拿到 ROLE_ADMIN —— 修复前这里恒为空, 所有 hasRole('ADMIN') 永远拒绝");
        assertTrue(empDetails.getAuthorities().stream()
                .anyMatch(a -> "ROLE_USER".equals(a.getAuthority())));
    }

    // ------------------------------------------------------------------
    // 管理接口
    // ------------------------------------------------------------------

    @Test
    @DisplayName("普通员工访问管理接口一律 403")
    void employeeCannotReachAdminEndpoints() throws Exception {
        mvc.perform(as(get("/leave/list").param("year", "2026"), employee()))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/leave/accounts").param("year", "2026"), employee()))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/leave/users"), employee()))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/leave/updateAccount")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"lastYearBalance\":99}"), employee()))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/leave/add-record")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":1,\"type\":\"ADJUSTMENT_ADD\",\"days\":99,"
                        + "\"startDate\":\"2026-01-01\",\"endDate\":\"2026-01-01\"}"), employee()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("管理员可以访问管理接口")
    void adminCanReachAdminEndpoints() throws Exception {
        mvc.perform(as(get("/leave/list").param("year", "2026"), admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(as(get("/leave/users"), admin()))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // 自助接口: userId 只有管理员能指定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("员工查自己的账户正常")
    void employeeCanReadOwnAccount() throws Exception {
        mvc.perform(as(get("/leave/account")
                .param("userId", String.valueOf(employee().getId()))
                .param("year", "2026"), employee()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("员工传别人的 userId 会被拒绝 (IDOR)")
    void employeeCannotReadOthersAccount() throws Exception {
        mvc.perform(as(get("/leave/account")
                .param("userId", String.valueOf(otherEmployee().getId()))
                .param("year", "2026"), employee()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mvc.perform(as(get("/leave/history")
                .param("userId", String.valueOf(otherEmployee().getId())), employee()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("管理员可以指定任意 userId")
    void adminCanReadAnyAccount() throws Exception {
        mvc.perform(as(get("/leave/account")
                .param("userId", String.valueOf(otherEmployee().getId()))
                .param("year", "2026"), admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("不带 userId 时回落到本人")
    void omittingUserIdFallsBackToSelf() throws Exception {
        mvc.perform(as(get("/leave/account").param("year", "2026"), employee()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(employee().getId()));
    }

    // ------------------------------------------------------------------
    // 未登录
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未携带 token 一律 401")
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/leave/account").param("year", "2026"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/leave/list").param("year", "2026"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // 自助初始化: 只能给自己、只能当年、且账户不存在时
    // ------------------------------------------------------------------

    @Test
    @DisplayName("员工不能借初始化重算他人或历史年度")
    void employeeInitIsConstrained() throws Exception {
        int lastYear = LocalDate.now().getYear() - 1;

        // 指定他人 id: 授权失败, 真正的 403
        mvc.perform(as(post("/leave/init")
                .param("userId", String.valueOf(otherEmployee().getId()))
                .param("year", String.valueOf(LocalDate.now().getYear())), employee()))
                .andExpect(status().isForbidden());

        mvc.perform(as(post("/leave/init").param("year", String.valueOf(lastYear)), employee()))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("当年度")));
    }
}
