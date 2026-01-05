package com.leave.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.leave.system.entity.RoleMenu;
import com.leave.system.entity.SysMenu;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.RoleMenuMapper;
import com.leave.system.mapper.SysMenuMapper;
import com.leave.system.mapper.SysUserMapper;
import com.leave.system.service.MenuService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements MenuService {

    private static final Logger log = LoggerFactory.getLogger(MenuServiceImpl.class);

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
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getRoleId() == null) {
            return List.of();
        }

        List<Long> menuIds = getMenuIdsByRoleId(user.getRoleId());
        if (menuIds.isEmpty()) {
            return List.of();
        }

        List<SysMenu> allMenus = menuMapper.selectBatchIds(menuIds);
        return buildTree(allMenus);
    }

    @Override
    public List<SysMenu> getMenuTree() {
        List<SysMenu> allMenus = menuMapper.selectAllMenus();
        return buildTree(allMenus);
    }

    private List<SysMenu> buildTree(List<SysMenu> menus) {
        List<SysMenu> tree = new ArrayList<>();
        for (SysMenu menu : menus) {
            if (menu.getParentId() == 0) {
                menu.setChildren(getChildren(menu.getId(), menus));
                tree.add(menu);
            }
        }
        return tree;
    }

    private List<SysMenu> getChildren(Long parentId, List<SysMenu> menus) {
        List<SysMenu> children = new ArrayList<>();
        for (SysMenu menu : menus) {
            if (menu.getParentId().equals(parentId)) {
                menu.setChildren(getChildren(menu.getId(), menus));
                children.add(menu);
            }
        }
        return children;
    }

    @Override
    public List<Long> getMenuIdsByRoleId(Long roleId) {
        List<RoleMenu> roleMenus = roleMenuMapper.selectByRoleId(roleId);
        return roleMenus.stream()
                .map(RoleMenu::getMenuId)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignMenusToRole(Long roleId, List<Long> menuIds) {
        roleMenuMapper.deleteByRoleId(roleId);
        if (menuIds != null && !menuIds.isEmpty()) {
            for (Long menuId : menuIds) {
                RoleMenu roleMenu = new RoleMenu();
                roleMenu.setRoleId(roleId);
                roleMenu.setMenuId(menuId);
                roleMenuMapper.insert(roleMenu);
            }
        }
    }

    @Override
    public void addMenu(SysMenu menu) {
        this.save(menu);
    }

    @Override
    public void updateMenu(SysMenu menu) {
        this.updateById(menu);
    }

    @Override
    public void deleteMenu(Long id) {
        this.removeById(id);
    }
}
