package com.leave.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.leave.system.entity.SysMenu;
import java.util.List;

public interface MenuService extends IService<SysMenu> {
    /**
     * 获取用户可见的菜单树
     */
    List<SysMenu> getUserMenus(Long userId);

    /**
     * 获取全量菜单树
     */
    List<SysMenu> getMenuTree();

    /**
     * 获取角色已分配的菜单ID列表
     */
    List<Long> getMenuIdsByRoleId(Long roleId);

    /**
     * 为角色分配菜单
     */
    void assignMenusToRole(Long roleId, List<Long> menuIds);

    void addMenu(SysMenu menu);

    void updateMenu(SysMenu menu);

    void deleteMenu(Long id);
}
