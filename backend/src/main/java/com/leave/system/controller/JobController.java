package com.leave.system.controller;

import com.leave.system.common.Result;
import com.leave.system.entity.SysJob;
import com.leave.system.service.SysJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/job")
public class JobController {

    @Autowired
    private SysJobService jobService;

    @GetMapping("/list")
    public Result<List<SysJob>> listJobs() {
        return Result.success(jobService.getAllJobs());
    }

    @GetMapping("/{id}")
    public Result<SysJob> getJob(@PathVariable Long id) {
        return Result.success(jobService.getJobById(id));
    }

    @PostMapping("/add")
    public Result<Void> addJob(@RequestBody SysJob job) {
        jobService.addJob(job);
        return Result.success(null, "任务创建成功");
    }

    @PostMapping("/update")
    public Result<Void> updateJob(@RequestBody SysJob job) {
        jobService.updateJob(job);
        return Result.success(null, "任务更新成功");
    }

    @PostMapping("/delete/{id}")
    public Result<Void> deleteJob(@PathVariable Long id) {
        jobService.deleteJob(id);
        return Result.success(null, "任务删除成功");
    }

    @PostMapping("/run/{id}")
    public Result<Void> runJob(@PathVariable Long id) {
        jobService.runJob(id);
        return Result.success(null, "任务执行成功");
    }

    @PostMapping("/changeStatus")
    public Result<Void> changeStatus(@RequestParam Long id, @RequestParam Integer status) {
        jobService.changeStatus(id, status);
        return Result.success(null, status == 0 ? "任务已恢复" : "任务已暂停");
    }
}
