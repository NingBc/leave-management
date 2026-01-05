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

    List<LeaveRecord> selectByUserIdAndYear(@Param("userId") Long userId, @Param("year") Integer year);

    List<LeaveRecord> selectRecordsForCarryOver(@Param("userId") Long userId, @Param("year") Integer year);

    List<LeaveRecord> selectExpiringRecords(@Param("userId") Long userId, @Param("expiryDate") LocalDate expiryDate);

    List<LeaveRecord> selectUsageRecordsForExpiryCleanup(@Param("userId") Long userId,
            @Param("expiryDate") LocalDate expiryDate, @Param("anchorTime") LocalDateTime anchorTime);

    List<LeaveRecord> selectExpiredRecordsByDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    List<LeaveRecord> selectFloatingRecordsForCleanup(@Param("userId") Long userId);

    long countAnnualLeaveUsage(@Param("userId") Long userId, @Param("startDate") LocalDate startDate,
            @Param("days") BigDecimal days);

    LeaveRecord selectCarryOverRecord(@Param("userId") Long userId, @Param("date") LocalDate date);

    long countDuplicateRecord(@Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("type") String type,
            @Param("days") BigDecimal days);

    List<LeaveRecord> selectRecordsByYear(@Param("userId") Long userId, @Param("year") Integer year);

    List<LeaveRecord> selectAvailableBalances(@Param("userId") Long userId);

    List<LeaveRecord> selectUsageRecords(@Param("userId") Long userId);

    List<LeaveRecord> selectFloatingRecords(@Param("userId") Long userId);

    List<LeaveRecord> findAllRecords();

    List<LeaveRecord> selectHistory(@Param("userId") Long userId, @Param("year") Integer year);

    int insertRecord(LeaveRecord record);

    int updateRecord(LeaveRecord record);
}
