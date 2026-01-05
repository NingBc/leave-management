-- ================================================================
-- 2024年年假结转记录插入脚本 (结转至2025年度)
-- ================================================================
-- 说明: 
-- 1. 该脚本用于将 2024 年度的剩余年假结转到 2025 年度。
-- 2. 在系统中表现为类型为 'CARRY_OVER' 的记录。
-- 3. 过期日期设置为 2025-12-31 (即在 2025 年底过期)。
-- 4. 包含两种方式：从账户表同步 (推荐) 和 手动指定。

USE leave_system;

-- 方式一：从 leave_account 表同步 (如果账户表中已经有上年结余数据)
-- ----------------------------------------------------------------
-- 该语句会将 2025 年度账户中的 last_year_balance 同步为 leave_record 记录
-- 只有结余大于 0 的用户会被执行。

INSERT INTO leave_record (user_id, start_date, end_date, days, type, remarks, expiry_date, create_time)
SELECT 
    user_id, 
    '2025-01-01',           -- 结转生效日期
    '2025-01-01',           -- 结转结束日期
    last_year_balance,      -- 结转天数
    'CARRY_OVER',           -- 类型
    '2024年年假结转 (系统同步)', -- 备注
    '2025-12-31',           -- 过期日期 (2025年底清理)
    '2025-01-01 00:00:01'   -- 创建时间
FROM leave_account
WHERE year = 2025 
  AND last_year_balance > 0
  AND user_id NOT IN (
      -- 幂等性检查：防止重复插入已存在的 2025 结转记录
      SELECT user_id FROM leave_record 
      WHERE type = 'CARRY_OVER' 
      AND start_date = '2025-01-01'
  );

-- ----------------------------------------------------------------
-- 方式二：手动插入示例 (针对特定用户)
-- ----------------------------------------------------------------
/*
INSERT INTO leave_record (user_id, start_date, end_date, days, type, remarks, expiry_date, create_time) VALUES
(1, '2025-01-01', '2025-01-01', 5.5, 'CARRY_OVER', '2024年年假结转 (手动录入)', '2025-12-31', '2025-01-01 00:00:01'),
(2, '2025-01-01', '2025-01-01', 3.0, 'CARRY_OVER', '2024年年假结转 (手动录入)', '2025-12-31', '2025-01-01 00:00:01');
*/

-- ----------------------------------------------------------------
-- 执行后建议检查：
-- ----------------------------------------------------------------
-- SELECT u.username, r.days, r.expiry_date 
-- FROM leave_record r 
-- JOIN sys_user u ON r.user_id = u.id 
-- WHERE r.type = 'CARRY_OVER' AND r.start_date = '2025-01-01';

 bitumen
