-- Database Initialization
CREATE DATABASE IF NOT EXISTS leave_system DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE leave_system;

-- 1. Users Table
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE COMMENT 'Username',
    password VARCHAR(100) NOT NULL COMMENT 'Encrypted Password',
    real_name VARCHAR(50) COMMENT 'Real Name',
    employee_number VARCHAR(50) COMMENT 'Employee Number',
    first_work_date DATE COMMENT 'First Work Date',
    entry_date DATE COMMENT 'Entry Date',
    social_seniority INT DEFAULT 0 COMMENT 'Social Seniority (Years)',
    role_id BIGINT COMMENT 'Role ID',
    dingtalk_userid VARCHAR(100) COMMENT 'DingTalk User ID',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT 'Status: ACTIVE, RESIGNED',
    resignation_date DATE COMMENT 'Resignation Date',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
) COMMENT 'User Table';

-- 2. Roles Table
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL UNIQUE,
    role_key VARCHAR(50) NOT NULL UNIQUE COMMENT 'ROLE_ADMIN, ROLE_USER',
    description VARCHAR(100),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
) COMMENT 'Role Table';

-- 3. Menus Table
CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT DEFAULT 0,
    menu_name VARCHAR(50) NOT NULL,
    path VARCHAR(100),
    component VARCHAR(100),
    icon VARCHAR(50),
    order_num INT DEFAULT 0,
    deleted TINYINT DEFAULT 0
) COMMENT 'Menu Table';

-- 3.5 Role-Menu Association Table
CREATE TABLE IF NOT EXISTS role_menu (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, menu_id),
    FOREIGN KEY (role_id) REFERENCES sys_role(id) ON DELETE CASCADE,
    FOREIGN KEY (menu_id) REFERENCES sys_menu(id) ON DELETE CASCADE
) COMMENT 'Role-Menu Association Table';

-- 3.6 Scheduled Job Table
CREATE TABLE IF NOT EXISTS sys_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_name VARCHAR(100) NOT NULL COMMENT 'Job Name',
    job_group VARCHAR(50) DEFAULT 'DEFAULT' COMMENT 'Job Group',
    invoke_target VARCHAR(500) NOT NULL COMMENT 'Invocation Target (e.g., beanName.methodName())',
    cron_expression VARCHAR(255) NOT NULL COMMENT 'Cron Expression',
    status INT DEFAULT 0 COMMENT '0=Normal, 1=Paused',
    remark VARCHAR(500) COMMENT 'Remark',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
) COMMENT 'Scheduled Job Table';

-- 4. Leave Account Table (Annual Leave Balance)
CREATE TABLE IF NOT EXISTS leave_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT 'User ID',
    year INT NOT NULL COMMENT 'Year (e.g., 2025)',
    social_seniority INT COMMENT 'Social Seniority (Years)',
    standard_quota DECIMAL(5, 1) DEFAULT 0.0 COMMENT 'Standard Quota (年休假天数)',
    days_employed INT DEFAULT 0 COMMENT 'Days Employed in Year (年在职天数)',
    actual_quota DECIMAL(5, 1) DEFAULT 0.0 COMMENT 'Actual Quota (年假天数)',
    last_year_balance DECIMAL(5, 1) DEFAULT 0.0 COMMENT 'Last Year Balance (上年结余)',
    current_year_used DECIMAL(5, 1) DEFAULT 0.0 COMMENT 'Used from This Year',
    total_balance DECIMAL(5, 1) GENERATED ALWAYS AS (last_year_balance + (actual_quota - current_year_used)) VIRTUAL COMMENT 'Total Balance (年假余额)',
    deleted TINYINT DEFAULT 0,
    UNIQUE KEY uk_user_year (user_id, year)
) COMMENT 'Leave Account Table';

-- 5. Leave Record Table (Application History)
CREATE TABLE IF NOT EXISTS leave_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    days DECIMAL(5, 1) NOT NULL,
    type VARCHAR(20) DEFAULT 'ANNUAL' COMMENT 'Leave Type',
    remarks VARCHAR(500) COMMENT '备注说明',
    expiry_date DATE COMMENT '年假过期日期 (2-year validity)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_user_year (user_id, start_date),
    INDEX idx_expiry (expiry_date),
) COMMENT 'Leave Record Table';

-- Initial Data
INSERT INTO sys_role (role_name, role_key, description) VALUES 
('Administrator', 'ROLE_ADMIN', '系统管理员'),
('Employee', 'ROLE_USER', '普通员工');

-- Admin User (password: 123456)
INSERT INTO sys_user (username, password, real_name, role_id, social_seniority, entry_date) VALUES 
('admin', '$2b$12$sDUO0pQ/tZBzMgNQWqVhwujpMkIWLuV97C/Gmb2Fi7GqoWQcXqXlu', 'Administrator', 1, 5, '2020-01-01');

-- Sample Menus
INSERT INTO sys_menu (parent_id, menu_name, path, component, icon, order_num) VALUES
(0, '首页', '/dashboard', 'Dashboard', 'Menu', 1),
(0, '系统管理', '/system', null, 'Setting', 2),
(2, '用户管理', '/system/user', 'system/User', null, 1),
(2, '角色管理', '/system/role', 'system/Role', null, 2),
(2, '菜单管理', '/system/menu', 'system/Menu', null, 3),
(0, '休假管理', '/leave', null, 'Calendar', 3),
(6, '我的休假', '/leave/my', 'leave/MyLeave', null, 1),
(6, '休假管理(HR)', '/leave/manage', 'leave/LeaveManagement', null, 2),
(0, '系统监控', '/monitor', null, 'Monitor', 4),
(9, '定时任务', '/monitor/job', 'monitor/job/index', null, 1);

-- Assign all menus to admin role (role_id = 1)
INSERT INTO role_menu (role_id, menu_id) VALUES
(1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9), (1, 10);

-- Assign only "我的休假" menu to employee role (role_id = 2)
INSERT INTO role_menu (role_id, menu_id) VALUES
(2, 7);

-- Sample Scheduled Job: DingTalk Sync
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, status, remark) VALUES
('钉钉数据同步', 'DEFAULT', 'dingTalkService.syncLeaveData()', '0 0 10 ? * MON', 0, '每周一上午10点自动从钉钉同步年假数据');
