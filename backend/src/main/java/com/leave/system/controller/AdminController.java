package com.leave.system.controller;

import com.leave.system.common.Result;
import com.leave.system.entity.SysUser;
import com.leave.system.scheduled.ScheduledTasks;
import com.leave.system.service.LeaveService;
import com.leave.system.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin controller for manual operations
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final ScheduledTasks scheduledTasks;
    private final LeaveService leaveService;
    private final UserService userService;

    public AdminController(ScheduledTasks scheduledTasks, LeaveService leaveService, UserService userService) {
        this.scheduledTasks = scheduledTasks;
        this.leaveService = leaveService;
        this.userService = userService;
    }

    /**
     * Manually trigger expiry cleanup for a specific year
     * 
     * @param year Year to clean up (optional, defaults to last year)
     * @return Cleanup result
     */
    @PostMapping("/cleanup-expired")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<String> manualCleanupExpired(@RequestParam(required = false) Integer year) {
        try {
            log.info("📋 Admin manually triggered expiry cleanup for year: {}",
                    year != null ? year : "current-1");

            // 传了年份就按年份跑。此前这里一律调无参版本, 参数收了、日志也打了, 却被丢掉 ——
            // 管理员要求清理 2024, 实际清理的是「去年」。
            if (year != null) {
                scheduledTasks.cleanupExpiredLeaveBalances(String.valueOf(year));
            } else {
                scheduledTasks.cleanupExpiredLeaveBalances();
            }

            return Result.success("过期清理执行成功！请查看系统日志获取详细结果。");
        } catch (Exception e) {
            log.error("❌ Manual expiry cleanup failed", e);
            return Result.error("过期清理执行失败: " + e.getMessage());
        }
    }

    /**
     * Manually initialize/refresh leave accounts for a specific year
     * Useful for batch carry-over at year-end
     * 
     * @param year Year to initialize (required)
     * @return Initialization result
     */
    @PostMapping("/init-all-accounts")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> manualInitAllAccounts(@RequestParam Integer year) {
        try {
            log.info("📋 Admin manually triggered account initialization for year: {}", year);

            // Get all users
            List<SysUser> users = userService.getAllUsers();

            int successCount = 0;
            int failCount = 0;
            StringBuilder errors = new StringBuilder();

            for (SysUser user : users) {
                try {
                    // Skip resigned users
                    if ("RESIGNED".equals(user.getStatus())) {
                        continue;
                    }

                    leaveService.initYearlyAccount(user.getId(), year);
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    errors.append(String.format("用户%s失败: %s; ",
                            user.getRealName(), e.getMessage()));
                    log.error("Failed to init account for user {}", user.getId(), e);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("year", year);
            result.put("successCount", successCount);
            result.put("failCount", failCount);
            result.put("totalUsers", users.size());

            if (failCount > 0) {
                result.put("errors", errors.toString());
            }

            log.info("✅ Account initialization completed: {} success, {} failed",
                    successCount, failCount);

            return Result.success(result);
        } catch (Exception e) {
            log.error("❌ Manual account initialization failed", e);
            return Result.error("账户初始化执行失败: " + e.getMessage());
        }
    }

    /**
     * Get current task execution status (for monitoring)
     */
    @GetMapping("/task-status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> getTaskStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("currentDate", LocalDate.now());
        status.put("nextScheduledCleanup", "每年 1月1日 03:00");
        status.put("schedulingEnabled", true);

        return Result.success(status);
    }
}
