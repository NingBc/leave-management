package com.leave.system.controller;

import com.leave.system.common.Result;
import com.leave.system.entity.SysMenu;
import com.leave.system.mapper.SysMenuMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/menu")
public class MenuController {

    private final SysMenuMapper menuMapper;
    private final com.leave.system.service.MenuService menuService;

    public MenuController(SysMenuMapper menuMapper, com.leave.system.service.MenuService menuService) {
        this.menuMapper = menuMapper;
        this.menuService = menuService;
    }

    @GetMapping("/list")
    public Result<List<SysMenu>> list() {
        List<SysMenu> menus = menuMapper.selectList(null);
        return Result.success(buildTree(menus));
    }

    private List<SysMenu> buildTree(List<SysMenu> menus) {
        List<SysMenu> tree = new java.util.ArrayList<>();
        for (SysMenu menu : menus) {
            if (menu.getParentId() == 0) {
                menu.setChildren(getChildren(menu.getId(), menus));
                tree.add(menu);
            }
        }
        return tree;
    }

    private List<SysMenu> getChildren(Long parentId, List<SysMenu> menus) {
        List<SysMenu> children = new java.util.ArrayList<>();
        for (SysMenu menu : menus) {
            if (menu.getParentId().equals(parentId)) {
                menu.setChildren(getChildren(menu.getId(), menus));
                children.add(menu);
            }
        }
        return children;
    }

    @GetMapping("/user-menus")
    public Result<List<SysMenu>> getUserMenus(@RequestParam Long userId) {
        return Result.success(menuService.getUserMenus(userId));
    }

    @PostMapping("/add")
    public Result<Void> add(@RequestBody SysMenu menu) {
        menuMapper.insert(menu);
        return Result.success(null, "菜单添加成功");
    }

    @PutMapping("/update")
    public Result<Void> update(@RequestBody SysMenu menu) {
        menuMapper.updateById(menu);
        return Result.success(null, "菜单更新成功");
    }

    @DeleteMapping("/delete/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        menuMapper.deleteById(id);
        return Result.success(null, "菜单删除成功");
    }
}
