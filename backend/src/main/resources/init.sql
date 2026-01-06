-- ================================================================
-- 年假管理系统 - 数据库初始化脚本
-- 版本: 1.0
-- 说明: 本脚本用于系统首次部署，包含所有表结构、基础数据和必要配置
-- ================================================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS leave_system DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE leave_system;

-- ================================================================
-- 第一部分：表结构定义
-- ================================================================

-- 1. 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(100) NOT NULL COMMENT '加密密码',
    real_name VARCHAR(50) COMMENT '真实姓名',
    employee_number VARCHAR(50) COMMENT '工号',
    first_work_date DATE COMMENT '首次参加工作日期（用于计算社会工龄）',
    entry_date DATE COMMENT '本单位入职日期',
    social_seniority INT DEFAULT 0 COMMENT '社会工龄（年）',
    role_id BIGINT COMMENT '角色ID',
    dingtalk_userid VARCHAR(100) COMMENT '钉钉用户ID',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE=在职, RESIGNED=离职',
    resignation_date DATE COMMENT '离职日期',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_username (username),
    INDEX idx_status (status)
) COMMENT '用户表';

-- 2. 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL UNIQUE COMMENT '角色名称',
    role_key VARCHAR(50) NOT NULL UNIQUE COMMENT '角色标识: ROLE_ADMIN, ROLE_USER',
    description VARCHAR(100) COMMENT '角色描述',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
) COMMENT '角色表';

-- 3. 菜单表
CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT DEFAULT 0 COMMENT '父菜单ID，0表示顶级菜单',
    menu_name VARCHAR(50) NOT NULL COMMENT '菜单名称',
    path VARCHAR(100) COMMENT '路由路径',
    component VARCHAR(100) COMMENT '组件路径',
    perms VARCHAR(100) COMMENT '权限标识',
    icon VARCHAR(50) COMMENT '图标',
    order_num INT DEFAULT 0 COMMENT '排序',
    deleted TINYINT DEFAULT 0
) COMMENT '菜单表';

-- 4. 角色-菜单关联表
CREATE TABLE IF NOT EXISTS role_menu (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, menu_id),
    FOREIGN KEY (role_id) REFERENCES sys_role(id) ON DELETE CASCADE,
    FOREIGN KEY (menu_id) REFERENCES sys_menu(id) ON DELETE CASCADE
) COMMENT '角色菜单关联表';

-- 5. 定时任务表
CREATE TABLE IF NOT EXISTS sys_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_name VARCHAR(100) NOT NULL COMMENT '任务名称',
    job_group VARCHAR(50) DEFAULT 'DEFAULT' COMMENT '任务分组',
    invoke_target VARCHAR(500) NOT NULL COMMENT '调用目标（如: beanName.methodName()）',
    cron_expression VARCHAR(255) NOT NULL COMMENT 'Cron表达式',
    status INT DEFAULT 0 COMMENT '状态: 0=正常, 1=暂停',
    remark VARCHAR(500) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
) COMMENT '定时任务表';

-- 6. 年假账户表
CREATE TABLE IF NOT EXISTS leave_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    year INT NOT NULL COMMENT '年份（如2025）',
    social_seniority INT COMMENT '社会工龄（年）',
    standard_quota DECIMAL(5, 1) DEFAULT 0.0 COMMENT '标准额度（天）',
    days_employed INT DEFAULT 0 COMMENT '本年度在职天数',
    actual_quota DECIMAL(5, 1) DEFAULT 0.0 COMMENT '实际额度（天）',
    last_year_balance DECIMAL(5, 1) DEFAULT 0.0 COMMENT '上年结余（天）',
    current_year_used DECIMAL(5, 1) DEFAULT 0.0 COMMENT '本年已用（天）',
    total_balance DECIMAL(5, 1) GENERATED ALWAYS AS (last_year_balance + (actual_quota - current_year_used)) VIRTUAL COMMENT '年假余额（自动计算）',
    deleted TINYINT DEFAULT 0,
    UNIQUE KEY uk_user_year (user_id, year),
    INDEX idx_year (year)
) COMMENT '年假账户表';

-- 7. 休假记录表
CREATE TABLE IF NOT EXISTS leave_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    start_date DATE NOT NULL COMMENT '开始日期',
    end_date DATE NOT NULL COMMENT '结束日期',
    days DECIMAL(5, 1) NOT NULL COMMENT '天数（负数表示扣除）',
    type VARCHAR(20) DEFAULT 'ANNUAL' COMMENT '类型: ANNUAL=年假, ADJUSTMENT_ADD=额度增加, ADJUSTMENT_DEDUCT=额度扣除, CARRY_OVER=年假结转, EXPIRED=过期清理',
    remarks VARCHAR(500) COMMENT '备注',
    expiry_date DATE COMMENT '过期日期',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_user_year (user_id, start_date),
    INDEX idx_expiry (expiry_date)
) COMMENT '休假记录表';

-- ================================================================
-- 第二部分：基础数据初始化
-- ================================================================

-- 2.1 角色数据
INSERT INTO sys_role (role_name, role_key, description) VALUES 
('系统管理员', 'ROLE_ADMIN', '拥有系统所有权限'),
('普通员工', 'ROLE_USER', '只能查看和管理自己的年假');

-- 2.2 管理员账户
-- 用户名: admin
-- 密码: admin123
INSERT INTO sys_user (username, password, real_name, employee_number, first_work_date, entry_date, role_id, status) 
VALUES ('admin', '$2b$12$sDUO0pQ/tZBzMgNQWqVhwujpMkIWLuV97C/Gmb2Fi7GqoWQcXqXlu', '系统管理员', 'ADMIN001', '2020-01-01', '2020-01-01', 1, 'ACTIVE');

-- 2.3 菜单数据
INSERT INTO sys_menu (parent_id, menu_name, path, component, perms, icon, order_num) VALUES
-- 一级菜单
(0, '首页', '/dashboard', 'Dashboard', 'dashboard:view', 'HomeFilled', 1),
(0, '休假管理', '/leave', null, null, 'Calendar', 2),
(0, '系统管理', '/system', null, null, 'Setting', 3),
(0, '系统监控', '/monitor', null, null, 'Monitor', 4),

-- 休假管理子菜单
(2, '我的休假', '/leave/my', 'leave/MyLeave', 'leave:my:view', null, 1),
(2, '休假管理(管理员)', '/leave/manage', 'leave/ManageLeave', 'leave:manage:view', null, 2),

-- 系统管理子菜单
(3, '用户管理', '/system/user', 'system/User', 'system:user:view', null, 1),
(3, '角色管理', '/system/role', 'system/Role', 'system:role:view', null, 2),
(3, '菜单管理', '/system/menu', 'system/Menu', 'system:menu:view', null, 3),

-- 系统监控子菜单
(4, '定时任务', '/monitor/job', 'monitor/Job', 'monitor:job:view', null, 1);

-- 2.4 角色菜单关联
-- 管理员拥有所有菜单权限
INSERT INTO role_menu (role_id, menu_id) 
SELECT 1, id FROM sys_menu WHERE deleted = 0;

-- 普通员工只有"我的休假"权限
INSERT INTO role_menu (role_id, menu_id) VALUES (2, 5);

-- ================================================================
-- 第三部分：定时任务配置
-- ================================================================

-- 3.1 钉钉数据同步任务
-- 每周一上午10点执行
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, status, remark) VALUES
    ('钉钉数据同步', 'DEFAULT', 'dingTalkService.syncLeaveData()', '0 0 10 ? * MON', 0,
     '每周一上午10点自动从钉钉同步年假数据。从钉钉考勤系统拉取请假记录并更新到本地数据库。');


-- 3.2 年假过期清理任务
-- 每年1月1日凌晨3点执行
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, status, remark) VALUES
    ('年假过期清理', 'LEAVE', 'scheduledTasks.cleanupExpiredLeaveBalances()', '0 0 3 1 1 ?', 0,
     '每年1月1日凌晨3点自动清理已过期的年假余额（上年结转额度）。');
-- 3.3 年假账户批量初始化任务
-- 每年1月1日凌晨1点执行
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, status, remark) VALUES
('年假账户批量初始化', 'LEAVE', 'scheduledTasks.initAllAccounts(2026)', '0 0 1 1 1 ?', 1, 
'每年1月1日凌晨1点批量初始化所有员工的新年度账户（含结转计算）。注意：需手动更新年份参数！默认暂停状态。');



-- ================================================================
-- 完成提示
-- ================================================================
SELECT '数据库初始化完成！' AS message,
       '管理员账户: admin / 123456' AS admin_info,
       '请修改默认密码' AS security_note;


