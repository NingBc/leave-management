-- =============================================================================
-- V2: 移除 leave_account 上的两个派生列, 并登记每日额度刷新任务
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

-- 3) 登记每日额度刷新任务
--    当年额度按在职天数逐日累计。此前库里的 days_employed / actual_quota 只在有人
--    读取该用户账户时才更新, 同一批账户会散落在好几个月之前; 12/31 之后更是没人再访问
--    上年度账户, 结转基数就此冻结在残值上。这个任务让库里的值最多陈旧一天,
--    且不依赖有没有人登录。顺带把已能被额度覆盖的历史透支归位。
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, status, remark)
SELECT '年假额度每日刷新', 'DEFAULT', 'scheduledTasks.refreshCurrentYearQuota()', '0 30 0 * * ?', 0,
       '每日 00:30 刷新当年度全员在职天数与实际额度, 并将已被额度覆盖的历史透支归位。'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM sys_job WHERE invoke_target = 'scheduledTasks.refreshCurrentYearQuota()'
);

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
--   DELETE FROM sys_job WHERE invoke_target = 'scheduledTasks.refreshCurrentYearQuota()';
-- =============================================================================
