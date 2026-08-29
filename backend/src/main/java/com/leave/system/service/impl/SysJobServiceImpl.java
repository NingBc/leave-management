package com.leave.system.service.impl;

import com.leave.system.entity.SysJob;
import com.leave.system.mapper.SysJobMapper;
import com.leave.system.service.SysJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class SysJobServiceImpl implements SysJobService, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SysJobServiceImpl.class);

    @Autowired
    private SysJobMapper jobMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TaskScheduler taskScheduler;

    // Store scheduled tasks
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    @Override
    public void afterPropertiesSet() throws Exception {
        initScheduledTasks();
    }

    @Override
    public List<SysJob> getAllJobs() {
        return jobMapper.selectAllJobs();
    }

    @Override
    public SysJob getJobById(Long id) {
        return jobMapper.selectJobById(id);
    }

    @Override
    public void addJob(SysJob job) {
        job.setCreateTime(LocalDateTime.now());
        job.setUpdateTime(LocalDateTime.now());
        jobMapper.insertJob(job);
    }

    @Override
    public void updateJob(SysJob job) {
        job.setUpdateTime(LocalDateTime.now());
        jobMapper.updateJob(job);
        // Reschedule if needed
        if (job.getStatus() == 0) { // 0 for active
            rescheduleJob(job.getId());
        }
    }

    @Override
    public void deleteJob(Long id) {
        // Cancel scheduled task first
        cancelScheduledTask(id);
        jobMapper.deleteJobById(id);
    }

    @Async
    @Override
    public void runJob(Long id) {
        SysJob job = jobMapper.selectJobById(id);
        if (job == null) {
            log.error("Job not found: {}", id);
            return;
        }
        executeJob(job);
    }

    @Override
    public void changeStatus(Long id, Integer status) {
        SysJob job = jobMapper.selectJobById(id);
        if (job == null) {
            return;
        }
        job.setStatus(status);
        jobMapper.updateJob(job);

        if (status == 0) {
            // Resume: schedule the task
            rescheduleJob(id);
        } else {
            // Pause: cancel the task
            cancelScheduledTask(id);
        }
    }

    @Override
    public void initScheduledTasks() {
        log.info("Initializing scheduled tasks...");
        List<SysJob> jobs = jobMapper.selectActiveJobs();
        for (SysJob job : jobs) {
            scheduleJob(job);
        }
        log.info("Initialized {} scheduled tasks.", jobs.size());
    }

    @Override
    public void rescheduleJob(Long id) {
        cancelScheduledTask(id);
        SysJob job = jobMapper.selectJobById(id);
        if (job != null && job.getStatus() == 0) {
            scheduleJob(job);
        }
    }

    private void scheduleJob(SysJob job) {
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> executeJob(job),
                    new CronTrigger(job.getCronExpression()));
            scheduledTasks.put(job.getId(), future);
            log.info("Scheduled job: {} with cron: {}", job.getJobName(), job.getCronExpression());
        } catch (Exception e) {
            log.error("Failed to schedule job: {}", job.getJobName(), e);
        }
    }

    private void cancelScheduledTask(Long jobId) {
        ScheduledFuture<?> future = scheduledTasks.remove(jobId);
        if (future != null) {
            future.cancel(false);
            log.info("Cancelled scheduled task for job ID: {}", jobId);
        }
    }

    private void executeJob(SysJob job) {
        log.info("Executing job: {} - {}", job.getJobName(), job.getInvokeTarget());
        try {
            String invokeTarget = job.getInvokeTarget();
            // Parse: beanName.methodName() or beanName.methodName(param1,param2)
            int dotIndex = invokeTarget.indexOf('.');
            if (dotIndex == -1) {
                throw new IllegalArgumentException("Invalid invoke target format: " + invokeTarget);
            }

            String beanName = invokeTarget.substring(0, dotIndex);
            String methodWithParams = invokeTarget.substring(dotIndex + 1);

            int parenIndex = methodWithParams.indexOf('(');
            String methodName;
            Object[] params = new Object[0];

            if (parenIndex != -1) {
                methodName = methodWithParams.substring(0, parenIndex);
                // Extract parameters (simple comma-separated values for now)
                String paramsStr = methodWithParams.substring(parenIndex + 1, methodWithParams.indexOf(')'));
                if (!paramsStr.trim().isEmpty()) {
                    String[] paramArray = paramsStr.split(",");
                    params = new Object[paramArray.length];
                    for (int i = 0; i < paramArray.length; i++) {
                        params[i] = paramArray[i].trim();
                    }
                }
            } else {
                methodName = methodWithParams;
            }

            Object bean = applicationContext.getBean(beanName);
            Method method = findMethod(bean.getClass(), methodName, params.length);
            method.invoke(bean, params);

            recordRunResult(job.getId(), 0, "执行成功");
            log.info("Job executed successfully: {}", job.getJobName());
        } catch (Exception e) {
            // 反射调用把业务异常包在 InvocationTargetException 里, 取真实原因才有意义
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null)
                    ? e.getCause()
                    : e;
            log.error("Failed to execute job: {}", job.getJobName(), cause);
            recordRunResult(job.getId(), 1, describe(cause));
        }
    }

    /**
     * 把执行结果写回 sys_job, 让任务列表能直接看出上次跑成功没有。
     *
     * <p>
     * 此前失败只进日志。年终结算一年只跑一次, 悄悄失败等于全员额度和结转都错到明年,
     * 而没有任何地方能看出来。
     */
    private void recordRunResult(Long jobId, int status, String result) {
        try {
            SysJob job = jobMapper.selectJobById(jobId);
            if (job == null) {
                return;
            }
            job.setLastRunTime(LocalDateTime.now());
            job.setLastRunStatus(status);
            job.setLastRunResult(result);
            jobMapper.updateJob(job);
        } catch (Exception e) {
            log.error("Failed to record run result for job {}", jobId, e);
        }
    }

    private String describe(Throwable t) {
        String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        return msg.length() > 480 ? msg.substring(0, 480) + "..." : msg;
    }

    @Override
    public void updateLastRunTime(Long id) {
        SysJob job = jobMapper.selectJobById(id);
        if (job != null) {
            job.setLastRunTime(LocalDateTime.now());
            jobMapper.updateJob(job);
        }
    }

    private Method findMethod(Class<?> clazz, String methodName, int paramCount) throws NoSuchMethodException {
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == paramCount) {
                return method;
            }
        }
        throw new NoSuchMethodException("Method not found: " + methodName);
    }
}
