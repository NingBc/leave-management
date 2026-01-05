package com.leave.system.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leave.system.entity.RoleMenu;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoleMenuMapper extends BaseMapper<RoleMenu> {
    List<RoleMenu> selectByRoleId(Long roleId);

    void deleteByRoleId(Long roleId);
}
