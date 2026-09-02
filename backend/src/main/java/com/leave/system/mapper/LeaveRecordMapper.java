package com.leave.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leave.system.entity.LeaveRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.math.BigDecimal;

@Mapper
public interface LeaveRecordMapper extends BaseMapper<LeaveRecord> {

        List<LeaveRecord> selectExpiringRecords(@Param("userId") Long userId,
                        @Param("expiryDate") LocalDate expiryDate);

        List<LeaveRecord> selectUsageRecordsForExpiryCleanup(@Param("userId") Long userId,
                        @Param("expiryDate") LocalDate expiryDate, @Param("anchorTime") LocalDateTime anchorTime);

        List<LeaveRecord> selectExpiredRecordsByDate(@Param("userId") Long userId, @Param("date") LocalDate date);

        List<LeaveRecord> selectFloatingRecordsForCleanup(@Param("userId") Long userId,
                        @Param("from") LocalDate from, @Param("to") LocalDate to);

        BigDecimal sumAnnualLeaveUsage(@Param("userId") Long userId, @Param("date") LocalDate date);

        LeaveRecord selectCarryOverRecord(@Param("userId") Long userId, @Param("date") LocalDate date);

        List<LeaveRecord> selectRecordsByYear(@Param("userId") Long userId, @Param("year") Integer year);

        /**
         * 账本流水: from(含) 到 to(含) 之间发生的所有非 CARRY_OVER 流水。
         * to 传 null 表示不设上界。见 LeaveRecordMapper.xml 中该语句的注释。
         */
        List<LeaveRecord> selectLedgerRecords(@Param("userId") Long userId, @Param("from") LocalDate from,
                        @Param("to") LocalDate to);

        List<LeaveRecord> selectHistory(@Param("userId") Long userId, @Param("year") Integer year);

        int insertRecord(LeaveRecord record);

        int updateRecord(LeaveRecord record);
}
