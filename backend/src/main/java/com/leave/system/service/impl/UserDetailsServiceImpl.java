package com.leave.system.service.impl;

import com.leave.system.entity.SysRole;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.SysRoleMapper;
import com.leave.system.mapper.SysUserMapper;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;

    public UserDetailsServiceImpl(SysUserMapper userMapper, SysRoleMapper roleMapper) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser sysUser = userMapper.selectByUsername(username);
        if (sysUser == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        return new User(sysUser.getUsername(), sysUser.getPassword(), buildAuthorities(sysUser));
    }

    /**
     * Load the user's role as a Spring Security authority.
     * sys_role.role_key is already stored in ROLE_XXX form (ROLE_ADMIN / ROLE_USER),
     * which is exactly what hasRole('ADMIN') expects.
     */
    private List<GrantedAuthority> buildAuthorities(SysUser sysUser) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (sysUser.getRoleId() == null) {
            return authorities;
        }

        SysRole role = roleMapper.selectRoleById(sysUser.getRoleId());
        if (role == null || role.getRoleKey() == null || role.getRoleKey().isBlank()) {
            return authorities;
        }

        String roleKey = role.getRoleKey().trim();
        if (!roleKey.startsWith("ROLE_")) {
            roleKey = "ROLE_" + roleKey;
        }
        authorities.add(new SimpleGrantedAuthority(roleKey));
        return authorities;
    }
}
