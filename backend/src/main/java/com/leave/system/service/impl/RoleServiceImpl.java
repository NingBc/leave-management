package com.leave.system.service.impl;

import com.leave.system.entity.SysRole;
import com.leave.system.mapper.SysRoleMapper;
import com.leave.system.service.MenuService;
import com.leave.system.service.RoleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RoleServiceImpl implements RoleService {

    private final SysRoleMapper roleMapper;
    private final MenuService menuService;

    public RoleServiceImpl(SysRoleMapper roleMapper, MenuService menuService) {
        this.roleMapper = roleMapper;
        this.menuService = menuService;
    }

    @Override
    public List<SysRole> getAllRoles() {
        return roleMapper.selectActiveRoles();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addRole(SysRole role) {
        role.setCreateTime(LocalDateTime.now());
        role.setDeleted(0);
        roleMapper.insertRole(role);

        if (role.getMenuIds() != null && !role.getMenuIds().isEmpty()) {
            menuService.assignMenusToRole(role.getId(), role.getMenuIds());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRole(SysRole role) {
        role.setUpdateTime(LocalDateTime.now());
        roleMapper.updateRole(role);

        if (role.getMenuIds() != null) {
            menuService.assignMenusToRole(role.getId(), role.getMenuIds());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long id) {
        roleMapper.deleteRoleById(id);
    }

    @Override
    public SysRole getById(Long id) {
        return roleMapper.selectRoleById(id);
    }
}
