package com.leave.system.controller;

import com.leave.system.common.Result;
import com.leave.system.entity.SysMenu;
import com.leave.system.service.MenuService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/menu")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping("/list")
    public Result<List<SysMenu>> list() {
        return Result.success(menuService.getMenuTree());
    }

    @GetMapping("/user-menus")
    public Result<List<SysMenu>> getUserMenus(@RequestParam Long userId) {
        return Result.success(menuService.getUserMenus(userId));
    }

    @PostMapping("/add")
    public Result<Void> add(@RequestBody SysMenu menu) {
        menuService.addMenu(menu);
        return Result.success(null, "菜单添加成功");
    }

    @PutMapping("/update")
    public Result<Void> update(@RequestBody SysMenu menu) {
        menuService.updateMenu(menu);
        return Result.success(null, "菜单更新成功");
    }

    @DeleteMapping("/delete/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        menuService.deleteMenu(id);
        return Result.success(null, "菜单删除成功");
    }
}
