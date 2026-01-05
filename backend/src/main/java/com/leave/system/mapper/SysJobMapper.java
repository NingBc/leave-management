package com.leave.system.mapper;

import java.util.List;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leave.system.entity.SysJob;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysJobMapper extends BaseMapper<SysJob> {
    List<SysJob> selectActiveJobs();

    List<SysJob> selectAllJobs();

    int insertJob(SysJob job);

    int updateJob(SysJob job);

    int deleteJobById(Long id);

    SysJob selectJobById(Long id);
}
