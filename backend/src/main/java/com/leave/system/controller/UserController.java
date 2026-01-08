package com.leave.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leave.system.common.Result;
import com.leave.system.dto.ChangePasswordDTO;
import com.leave.system.dto.UserImportResult;
import com.leave.system.entity.SysUser;
import com.leave.system.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/user")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/list")
    public Result<Page<SysUser>> list(@RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(userService.getUserPage(current, size));
    }

    @GetMapping("/all")
    public Result<List<SysUser>> all() {
        return Result.success(userService.getAllUsers());
    }

    @GetMapping("/{id}")
    public Result<SysUser> getById(@PathVariable Long id) {
        return Result.success(userService.getById(id));
    }

    @PostMapping("/add")
    public Result<Void> add(@RequestBody SysUser user) {
        userService.addUser(user);
        return Result.success(null, "用户添加成功");
    }

    @PutMapping("/update")
    public Result<Void> update(@RequestBody SysUser user) {
        userService.updateUser(user);
        return Result.success(null, "用户更新成功");
    }

    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestBody ChangePasswordDTO dto) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        try {
            userService.changePassword(dto, username);
            return Result.success(null, "密码修改成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/delete/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return Result.success(null);
    }

    @PostMapping("/resign/{id}")
    public Result<Void> resign(@PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        try {
            userService.resignUser(id, body);
            return Result.success(null);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/activate/{id}")
    public Result<Void> activate(@PathVariable Long id) {
        try {
            userService.activateUser(id);
            return Result.success(null);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/import")
    public Result<UserImportResult> importUsers(@RequestParam("file") MultipartFile file) {
        try {
            UserImportResult result = userService.importUsers(file);
            return Result.success(result);
        } catch (Exception e) {
            log.error("导入用户失败", e);
            return Result.error("导入失败: " + e.getMessage());
        }
    }
}
