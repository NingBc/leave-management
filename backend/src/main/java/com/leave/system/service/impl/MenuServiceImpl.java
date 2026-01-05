package com.leave.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.leave.system.entity.RoleMenu;
import com.leave.system.entity.SysMenu;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.RoleMenuMapper;
import com.leave.system.mapper.SysMenuMapper;
import com.leave.system.mapper.SysUserMapper;
import com.leave.system.service.MenuService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class MenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements MenuService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MenuServiceImpl.class);

    private final SysMenuMapper menuMapper;
    private final SysUserMapper userMapper;
    private final RoleMenuMapper roleMenuMapper;

    public MenuServiceImpl(SysMenuMapper menuMapper, SysUserMapper userMapper, RoleMenuMapper roleMenuMapper) {
        this.menuMapper = menuMapper;
        this.userMapper = userMapper;
        this.roleMenuMapper = roleMenuMapper;
    }

    @Override
    public List<SysMenu> getUserMenus(Long userId) {
        log.info("getUserMenus called for userId: {}", userId);
        // 1. 获取用户的角色ID
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            log.info("User not found for userId: {}", userId);
            return List.of();
        }
        log.info("User found: {}, RoleId: {}", user.getUsername(), user.getRoleId());

        if (user.getRoleId() == null) {
            log.info("User has no role assigned.");
            return List.of();
        }

        // 2. 获取该角色分配的菜单ID列表
        List<Long> menuIds = getMenuIdsByRoleId(user.getRoleId());
        log.info("MenuIds for role {}: {}", user.getRoleId(), menuIds);

        if (menuIds.isEmpty()) {
            log.info("No menus assigned to role.");
            return List.of();
        }

        // 3. 查询这些菜单的详细信息
        List<SysMenu> menus = menuMapper.selectBatchIds(menuIds);
        log.info("Retrieved {} menu items.", menus.size());
        return menus;
    }

    @Override
    public List<Long> getMenuIdsByRoleId(Long roleId) {
        List<RoleMenu> roleMenus = roleMenuMapper.selectList(
                new QueryWrapper<RoleMenu>().eq("role_id", roleId));
        log.info("getMenuIdsByRoleId roleId={}, found {} entries.", roleId, roleMenus.size());
        return roleMenus.stream()
                .map(RoleMenu::getMenuId)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void assignMenusToRole(Long roleId, List<Long> menuIds) {
        // 1. 删除该角色的所有现有菜单分配
        roleMenuMapper.delete(new QueryWrapper<RoleMenu>().eq("role_id", roleId));

        // 2. 插入新的菜单分配
        if (menuIds != null && !menuIds.isEmpty()) {
            for (Long menuId : menuIds) {
                RoleMenu roleMenu = new RoleMenu();
                roleMenu.setRoleId(roleId);
                roleMenu.setMenuId(menuId);
                roleMenuMapper.insert(roleMenu);
            }
        }
    }
}
