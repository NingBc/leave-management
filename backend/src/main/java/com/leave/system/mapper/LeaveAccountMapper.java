package com.leave.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leave.system.entity.LeaveAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface LeaveAccountMapper extends BaseMapper<LeaveAccount> {
    LeaveAccount selectAccountByUserIdAndYearIncludeDeleted(@Param("userId") Long userId, @Param("year") Integer year);

    LeaveAccount selectAccountByUserIdAndYear(@Param("userId") Long userId, @Param("year") Integer year);

    LeaveAccount selectLastYearAccount(@Param("userId") Long userId, @Param("year") Integer year);

    /** 软删指定年度之后的账户 (离职结算用: 当年及历史必须保留) */
    void deleteAccountsAfterYear(@Param("userId") Long userId, @Param("year") Integer year);

    List<Integer> selectDistinctYears();

    List<LeaveAccount> selectAccountsByYear(@Param("year") Integer year);

    int insertAccount(LeaveAccount account);

    int updateAccount(LeaveAccount account);
}
