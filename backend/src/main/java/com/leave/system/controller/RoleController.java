package com.leave.system.controller;

import com.leave.system.common.Result;
import com.leave.system.entity.SysRole;
import com.leave.system.mapper.SysRoleMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/system/role")
public class RoleController {

    private final SysRoleMapper roleMapper;
    private final com.leave.system.service.MenuService menuService;

    public RoleController(SysRoleMapper roleMapper, com.leave.system.service.MenuService menuService) {
        this.roleMapper = roleMapper;
        this.menuService = menuService;
    }

    @GetMapping("/list")
    public Result<List<SysRole>> list() {
        return Result.success(roleMapper.selectList(null));
    }

    @PostMapping("/add")
    public Result<Void> add(@RequestBody SysRole role) {
        role.setCreateTime(LocalDateTime.now());
        role.setDeleted(0);
        roleMapper.insert(role);

        if (role.getMenuIds() != null) {
            menuService.assignMenusToRole(role.getId(), role.getMenuIds());
        }

        return Result.success(null, "角色添加成功");
    }

    @PutMapping("/update")
    public Result<Void> update(@RequestBody SysRole role) {
        role.setUpdateTime(LocalDateTime.now());
        roleMapper.updateById(role);

        if (role.getMenuIds() != null) {
            menuService.assignMenusToRole(role.getId(), role.getMenuIds());
        }

        return Result.success(null, "角色更新成功");
    }

    @DeleteMapping("/delete/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        roleMapper.deleteById(id);
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
