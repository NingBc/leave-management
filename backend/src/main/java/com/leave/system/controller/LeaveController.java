package com.leave.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leave.system.common.Result;
import com.leave.system.dto.LeaveAccountDTO;
import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.service.LeaveService;
import com.leave.system.service.UserService;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/leave")
public class LeaveController {

    private final LeaveService leaveService;
    private final UserService userService;

    public LeaveController(LeaveService leaveService, UserService userService) {
        this.leaveService = leaveService;
        this.userService = userService;
    }

    @GetMapping("/account")
    public Result<LeaveAccountDTO> getAccount(@RequestParam Long userId, @RequestParam int year) {
        return Result.success(leaveService.getAccount(userId, year));
    }

    @PostMapping("/apply")
    public Result<Void> applyLeave(@RequestBody ApplyRequest request) {
        leaveService.applyLeave(request.getUserId(), request.getStartDate(), request.getEndDate());
        return Result.success(null, "申请休假成功");
    }

    @GetMapping("/history")
    public Result<List<LeaveRecord>> getHistory(@RequestParam Long userId,
            @RequestParam(required = false) Integer year) {
        return Result.success(leaveService.getHistory(userId, year));
    }

    @GetMapping("/list")
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
    public Result<Void> updateRecord(@RequestBody LeaveRecord record) {
        leaveService.updateRecord(record);
        return Result.success(null, "记录更新成功");
    }

    @PostMapping("/add-record")
    public Result<Void> addRecord(@RequestBody LeaveRecord record) {
        leaveService.addRecord(record);
        return Result.success(null, "记录添加成功");
    }

    @GetMapping("/accounts")
    public Result<List<LeaveAccountDTO>> getAllAccounts(@RequestParam int year) {
        return Result.success(leaveService.getAllAccounts(year));
    }

    @PostMapping("/updateAccount")
    public Result<Void> updateAccount(@RequestBody LeaveAccount account) {
        leaveService.updateAccount(account);
        return Result.success(null, "账户更新成功");
    }

    @GetMapping("/users")
    public Result<List<SysUser>> getAllUsers() {
        return Result.success(userService.getAllUsers());
    }

    @GetMapping("/available-years")
    public Result<List<Integer>> getAvailableYears() {
        return Result.success(leaveService.getAllAvailableYears());
    }

    @PostMapping("/init")
    public Result<Void> initAccount(@RequestParam Long userId, @RequestParam Integer year) {
        leaveService.initYearlyAccount(userId, year);
        return Result.success(null, "账户初始化成功");
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
