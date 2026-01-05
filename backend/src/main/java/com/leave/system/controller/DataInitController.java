package com.leave.system.controller;

import com.leave.system.entity.RoleMenu;
import com.leave.system.mapper.RoleMenuMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/debug")
public class DataInitController {

    private final RoleMenuMapper roleMenuMapper;

    public DataInitController(RoleMenuMapper roleMenuMapper) {
        this.roleMenuMapper = roleMenuMapper;
    }

    @GetMapping("/init-role-menu")
    public String initRoleMenu() {
        // Admin Role (1) - All Menus (1-8)
        List<Long> adminMenus = Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        for (Long menuId : adminMenus) {
            try {
                RoleMenu rm = new RoleMenu();
                rm.setRoleId(1L);
                rm.setMenuId(menuId);
                roleMenuMapper.insert(rm);
            } catch (Exception e) {
                // Ignore duplicates
            }
        }

        // Employee Role (2) - My Leave (7)
        try {
            RoleMenu rm = new RoleMenu();
            rm.setRoleId(2L);
            rm.setMenuId(7L);
            roleMenuMapper.insert(rm);
        } catch (Exception e) {
            // Ignore duplicates
        }

        return "Role-Menu data initialized (duplicates ignored)";
    }
}
