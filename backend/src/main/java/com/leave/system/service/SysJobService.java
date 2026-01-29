package com.leave.system.service;

import com.leave.system.entity.SysJob;
import java.util.List;

public interface SysJobService {
    /**
     * Get all jobs
     */
    List<SysJob> getAllJobs();

    /**
     * Get job by ID
     */
    SysJob getJobById(Long id);

    /**
     * Add a new job
     */
    void addJob(SysJob job);

    /**
     * Update job
     */
    void updateJob(SysJob job);

    /**
     * Delete job
     */
    void deleteJob(Long id);

    /**
     * Run job immediately (one-time execution)
     */
    void runJob(Long id);

    /**
     * Change job status (pause/resume)
     */
    void changeStatus(Long id, Integer status);

    /**
     * Initialize scheduled tasks from database
     */
    void initScheduledTasks();

    /**
     * Reschedule a specific job
     */
    void rescheduleJob(Long id);

    /**
     * Update the last run time of a job to now
     */
    void updateLastRunTime(Long id);
}
