package com.leave.system.controller;

import com.leave.system.common.Result;
import com.leave.system.entity.SysRole;
import com.leave.system.service.RoleService;
import com.leave.system.service.MenuService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/role")
public class RoleController {

    private final RoleService roleService;
    private final MenuService menuService;

    public RoleController(RoleService roleService, MenuService menuService) {
        this.roleService = roleService;
        this.menuService = menuService;
    }

    @GetMapping("/list")
    public Result<List<SysRole>> list() {
        return Result.success(roleService.getAllRoles());
    }

    @PostMapping("/add")
    public Result<Void> add(@RequestBody SysRole role) {
        roleService.addRole(role);
        return Result.success(null, "角色添加成功");
    }

    @PutMapping("/update")
    public Result<Void> update(@RequestBody SysRole role) {
        roleService.updateRole(role);
        return Result.success(null, "角色更新成功");
    }

    @DeleteMapping("/delete/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.deleteRole(id);
        return Result.success(null, "角色删除成功");
    }

    @GetMapping("/{roleId}/menus")
    public Result<List<Long>> getRoleMenus(@PathVariable Long roleId) {
        return Result.success(menuService.getMenuIdsByRoleId(roleId));
    }

    @PutMapping("/{roleId}/menus")
    public Result<Void> assignMenus(@PathVariable Long roleId, @RequestBody List<Long> menuIds) {
        menuService.assignMenusToRole(roleId, menuIds);
        return Result.success(null, "菜单分配成功");
    }
}
