package com.leave.system.service;

import com.leave.system.entity.SysMenu;
import java.util.List;

public interface MenuService {
    /**
     * 获取用户可见的菜单列表（根据用户的角色）
     */
    List<SysMenu> getUserMenus(Long userId);

    /**
     * 获取角色已分配的菜单ID列表
     */
    List<Long> getMenuIdsByRoleId(Long roleId);

    /**
     * 为角色分配菜单
     */
    void assignMenusToRole(Long roleId, List<Long> menuIds);
}
