package com.leave.system.service;

import com.leave.system.entity.SysRole;
import java.util.List;

public interface RoleService {
    List<SysRole> getAllRoles();

    void addRole(SysRole role);

    void updateRole(SysRole role);

    void deleteRole(Long id);

    SysRole getById(Long id);
}
