package com.leave.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leave.system.common.Result;
import com.leave.system.dto.LeaveAccountDTO;
import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.exception.BusinessException;
import com.leave.system.security.CurrentUserService;
import com.leave.system.service.LeaveService;
import com.leave.system.service.UserService;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/leave")
public class LeaveController {

    private final LeaveService leaveService;
    private final UserService userService;
    private final CurrentUserService currentUserService;

    public LeaveController(LeaveService leaveService, UserService userService,
            CurrentUserService currentUserService) {
        this.leaveService = leaveService;
        this.userService = userService;
        this.currentUserService = currentUserService;
    }

    // ---------------------------------------------------------------------
    // 员工自助接口：userId 只有管理员可以指定，普通员工一律回落到本人
    // ---------------------------------------------------------------------

    @GetMapping("/account")
    public Result<LeaveAccountDTO> getAccount(@RequestParam(required = false) Long userId,
            @RequestParam int year) {
        Long targetUserId = currentUserService.resolveTargetUserId(userId);
        return Result.success(leaveService.getAccount(targetUserId, year));
    }

    @GetMapping("/history")
    public Result<List<LeaveRecord>> getHistory(@RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer year) {
        Long targetUserId = currentUserService.resolveTargetUserId(userId);
        return Result.success(leaveService.getHistory(targetUserId, year));
    }

    @GetMapping("/available-years")
    public Result<List<Integer>> getAvailableYears() {
        return Result.success(leaveService.getAllAvailableYears());
    }

    /**
     * 初始化年假账户。
     * 管理员可初始化任意员工的任意年度；普通员工只能为自己初始化「当年且尚不存在」的账户,
     * 以免重跑初始化把管理员手工修正过的上年结余覆盖掉。
     */
    @PostMapping("/init")
    public Result<Void> initAccount(@RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();

        // 员工指定他人 id 直接拒绝, 不静默改成自己 —— 否则调用方以为初始化了别人
        userId = currentUserService.resolveTargetUserId(userId);

        if (!currentUserService.isAdmin()) {
            if (targetYear != LocalDate.now().getYear()) {
                throw new BusinessException("只能初始化当年度账户");
            }
            if (leaveService.getAccount(userId, targetYear).getId() != null) {
                throw new BusinessException("账户已存在，如需重算请联系管理员");
            }
        }

        leaveService.initYearlyAccount(userId, targetYear);
        return Result.success(null, "账户初始化成功");
    }

    // ---------------------------------------------------------------------
    // 管理接口：仅管理员
    // ---------------------------------------------------------------------

    @PostMapping("/apply")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> applyLeave(@RequestBody ApplyRequest request) {
        if (request.getUserId() == null || request.getStartDate() == null || request.getEndDate() == null) {
            throw new BusinessException("用户、开始日期和结束日期均不能为空");
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new BusinessException("结束日期不能早于开始日期");
        }
        leaveService.applyLeave(request.getUserId(), request.getStartDate(), request.getEndDate());
        return Result.success(null, "申请休假成功");
    }

    @GetMapping("/list")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Page<LeaveAccountDTO>> list(
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        if (year == null) {
            year = LocalDate.now().getYear();
        }

        Page<LeaveAccountDTO> page = leaveService.getAllAccountsPage(year, current, size);
        return Result.success(page);
    }

    @PostMapping("/update-record")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateRecord(@RequestBody LeaveRecord record) {
        if (record.getId() == null) {
            throw new BusinessException("记录 ID 不能为空");
        }
        leaveService.updateRecord(record);
        return Result.success(null, "记录更新成功");
    }

    @PostMapping("/add-record")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> addRecord(@RequestBody LeaveRecord record) {
        leaveService.addRecord(record);
        return Result.success(null, "记录添加成功");
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<LeaveAccountDTO>> getAllAccounts(@RequestParam int year) {
        return Result.success(leaveService.getAllAccounts(year));
    }

    @PostMapping("/updateAccount")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateAccount(@RequestBody LeaveAccount account) {
        leaveService.updateAccount(account);
        return Result.success(null, "账户更新成功");
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<SysUser>> getAllUsers() {
        return Result.success(userService.getAllUsers());
    }

    @Data
    static class ApplyRequest {
        private Long userId;
        private LocalDate startDate;
        private LocalDate endDate;

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public LocalDate getStartDate() {
            return startDate;
        }

        public void setStartDate(LocalDate startDate) {
            this.startDate = startDate;
        }

        public LocalDate getEndDate() {
            return endDate;
        }

        public void setEndDate(LocalDate endDate) {
            this.endDate = endDate;
        }
    }
}
