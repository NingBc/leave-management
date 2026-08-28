-- =============================================================================
-- V2: 移除 leave_account 上的两个派生列, 并让定时任务的执行结果可见
--
-- 背景
--   current_year_used 只在管理员打开编辑弹窗并保存时才会写入, 平时恒为 0;
--   而 total_balance 是依赖它的 MySQL 生成列:
--       total_balance = last_year_balance + (actual_quota - current_year_used)
--   于是库里的 total_balance 对所有休过假的人都偏高, 实测最多偏高 8.5 天
--   (林颖: 库里 16.0, 真实 7.5)。页面不受影响 —— 接口每次请求都按流水实时算 ——
--   但凡直接查库导报表/对账, 拿到的就是错的。
--
--   结论: 「本年已用」「年假余额」是派生值, 只由接口计算, 不落库。
--
-- 执行顺序注意
--   total_balance 是引用 current_year_used 的生成列, 必须先删它, 否则 MySQL 报
--   ERROR 3108 (column is used by a generated column)。
--
-- 环境: MySQL 5.7
-- 建议: 执行前先备份 leave_account
--       mysqldump -h <host> -u <user> -p leave_system leave_account > leave_account_backup.sql
-- =============================================================================

USE leave_system;

-- 1) 先删生成列
ALTER TABLE leave_account DROP COLUMN total_balance;

-- 2) 再删被它引用的列
ALTER TABLE leave_account DROP COLUMN current_year_used;

-- 3) sys_job 增加执行结果字段
--    此前任务失败只进日志。年终结算一年只跑一次, 悄悄失败等于全员额度和结转
--    都错到明年, 而任务列表上看不出任何异常。
ALTER TABLE sys_job
    ADD COLUMN last_run_status TINYINT DEFAULT NULL COMMENT '上次执行结果: 0=成功, 1=失败',
    ADD COLUMN last_run_result VARCHAR(500) DEFAULT NULL COMMENT '上次执行结果说明';

-- 4) 停用重复的年初初始化任务
--    scheduledTasks.cleanupExpiredLeaveBalances() 内部已经会在清理后调用 initAllAccounts,
--    单独再跑一次 initAllAccounts 是重复的(幂等, 不会出错, 但白跑一遍)。
UPDATE sys_job SET status = 1,
       remark = CONCAT(COALESCE(remark, ''), ' [已停用: 清理任务内部已包含初始化]')
WHERE invoke_target = 'scheduledTasks.initAllAccounts()' AND status = 0;

-- =============================================================================
-- 回滚 (如需)
--   ALTER TABLE leave_account
--     ADD COLUMN current_year_used DECIMAL(5,1) DEFAULT 0.0 COMMENT 'Used from This Year',
--     ADD COLUMN total_balance DECIMAL(5,1)
--       GENERATED ALWAYS AS (last_year_balance + (actual_quota - current_year_used)) VIRTUAL;
--   UPDATE sys_job SET status = 0 WHERE invoke_target = 'scheduledTasks.initAllAccounts()';
--   ALTER TABLE sys_job DROP COLUMN last_run_status, DROP COLUMN last_run_result;
-- =============================================================================
