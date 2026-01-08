INSERT INTO leave_record (user_id, start_date, end_date, days, type, remarks, expiry_date, create_time)
SELECT
    user_id,
    '2026-01-01',           -- 结转生效日期
    '2026-01-01',           -- 结转结束日期
    last_year_balance,      -- 结转天数
    'CARRY_OVER',           -- 类型
    '2025年年假结转 (系统同步)', -- 备注
    '2026-12-31',           -- 过期日期 (2025年底清理)
    '2026-01-01 00:00:01'   -- 创建时间
FROM leave_account
WHERE year = 2026
  AND last_year_balance > 0
  AND user_id NOT IN (
-- 幂等性检查：防止重复插入已存在的 2025 结转记录
    SELECT user_id FROM leave_record
    WHERE type = 'CARRY_OVER'
  AND start_date = '2026-01-01'
    );