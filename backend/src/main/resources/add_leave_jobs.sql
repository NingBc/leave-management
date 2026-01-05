-- 在 schema.sql 现有的定时任务数据后添加新的任务

-- 年假过期清理任务（每年1月1日3点执行）
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, status, remark) VALUES
('年假过期清理', 'LEAVE', 'scheduledTasks.cleanupExpiredLeaveBalances()', '0 0 3 1 1 ?', 0, '每年1月1日凌晨3点自动清理已过期的年假余额');

-- 年假账户初始化任务（每年1月1日1点执行 - 比清理早2小时）
-- 注意: 需要传入年份参数，建议手动执行或通过管理界面指定年份
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, status, remark) VALUES
('年假账户批量初始化', 'LEAVE', 'scheduledTasks.initAllAccounts(2026)', '0 0 1 1 1 ?', 1, '每年1月1日凌晨1点批量初始化所有员工的新年度账户（含结转计算）。注意：需手动更新年份参数！默认暂停状态。');
