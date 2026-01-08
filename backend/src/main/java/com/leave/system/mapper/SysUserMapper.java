package com.leave.system.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leave.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    SysUser selectByUsername(String username);

    SysUser selectByEmployeeNumber(String employeeNumber);

    SysUser selectByDingtalkUserId(String dingtalkUserId);

    List<SysUser> selectAllUsers();

    Page<SysUser> selectAllUsersPage(Page<SysUser> page);

    List<SysUser> selectActiveUsers();

    Page<SysUser> selectActiveUsersPage(Page<SysUser> page);

    List<SysUser> selectUsersWithDingtalkId();

    int insertUser(SysUser user);

    int updateUser(SysUser user);

    int deleteUserById(Long id);

    SysUser selectUserById(Long id);
}
