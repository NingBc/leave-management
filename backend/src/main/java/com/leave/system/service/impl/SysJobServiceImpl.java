package com.leave.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.leave.system.entity.SysJob;
import com.leave.system.mapper.SysJobMapper;
import com.leave.system.service.SysJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
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
        return jobMapper.selectList(null);
    }

    @Override
    public SysJob getJobById(Long id) {
        return jobMapper.selectById(id);
    }

    @Override
    public void addJob(SysJob job) {
        job.setStatus(1); // Default to paused
        jobMapper.insert(job);
    }

    @Override
    public void updateJob(SysJob job) {
        jobMapper.updateById(job);
        // Reschedule if needed
        if (job.getStatus() == 0) {
            rescheduleJob(job.getId());
        }
    }

    @Override
    public void deleteJob(Long id) {
        // Cancel scheduled task first
        cancelScheduledTask(id);
        jobMapper.deleteById(id);
    }

    @Override
    public void runJob(Long id) {
        SysJob job = jobMapper.selectById(id);
        if (job == null) {
            log.error("Job not found: {}", id);
            return;
        }
        executeJob(job);
    }

    @Override
    public void changeStatus(Long id, Integer status) {
        SysJob job = jobMapper.selectById(id);
        if (job == null) {
            return;
        }
        job.setStatus(status);
        jobMapper.updateById(job);

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
        List<SysJob> jobs = jobMapper.selectList(new QueryWrapper<SysJob>().eq("status", 0));
        for (SysJob job : jobs) {
            scheduleJob(job);
        }
        log.info("Initialized {} scheduled tasks.", jobs.size());
    }

    @Override
    public void rescheduleJob(Long id) {
        cancelScheduledTask(id);
        SysJob job = jobMapper.selectById(id);
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

            log.info("Job executed successfully: {}", job.getJobName());
        } catch (Exception e) {
            log.error("Failed to execute job: {}", job.getJobName(), e);
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
