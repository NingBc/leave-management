package com.leave.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leave.system.entity.LeaveAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LeaveAccountMapper extends BaseMapper<LeaveAccount> {
    LeaveAccount selectAccountByUserIdAndYearIncludeDeleted(@Param("userId") Long userId, @Param("year") Integer year);

    LeaveAccount selectAccountByUserIdAndYear(@Param("userId") Long userId, @Param("year") Integer year);
}
