package com.leave.system;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@SpringBootTest
public class DebugDebtTest {
    @Autowired
    private com.leave.system.scheduled.ScheduledTasks scheduledTasks;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private com.leave.system.service.LeaveService leaveService;

    @Test
    public void printDebtRecords() {
        // 0. Setup Data for testing
        System.out.println("🛠️ Setting up test data...");
        // Clear old records to have a clean state
        jdbcTemplate.update("DELETE FROM leave_account WHERE user_id IN (5, 6, 7)");
        jdbcTemplate.update("DELETE FROM leave_record WHERE user_id IN (5, 6, 7)");

        // User 6: Has 2025 account but 0 balance, and a negative floating record
        // (debt).
        // Also has a 2027 credit record.
        jdbcTemplate.update(
                "INSERT INTO leave_account (user_id, year, actual_quota, last_year_balance, deleted) VALUES (6, 2025, 0, 0, 0)");
        jdbcTemplate.update(
                "INSERT INTO leave_record (user_id, type, days, start_date, end_date, remarks, deleted) VALUES (6, 'ADJUSTMENT_DEDUCT', -1.0, '2025-01-01', '2025-01-01', 'Historical Debt', 0)");
        jdbcTemplate.update(
                "INSERT INTO leave_record (user_id, type, days, start_date, end_date, expiry_date, remarks, deleted) VALUES (6, 'ADJUSTMENT_ADD', 2.5, '2025-01-01', '2025-01-01', '2027-12-31', 'Future Credit', 0)");

        // User 7: Has 2025 account, 0 balance, and -2.0 debt. No future credits.
        jdbcTemplate.update(
                "INSERT INTO leave_account (user_id, year, actual_quota, last_year_balance, deleted) VALUES (7, 2025, 0, 0, 0)");
        jdbcTemplate.update(
                "INSERT INTO leave_record (user_id, type, days, start_date, end_date, remarks, deleted) VALUES (7, 'ANNUAL', -2.0, '2025-01-01', '2025-01-01', 'Historical Debt', 0)");

        // 1. Run cleanup for 2025
        System.out.println("🚀 Running cleanup for 2025...");
        scheduledTasks.cleanupExpiredLeaveBalances("2025");

        long[] userIds = { 6L, 7L };
        for (long userId : userIds) {
            System.out.println("--- RECORDS FOR USER " + userId + " ---");
            List<Map<String, Object>> records = jdbcTemplate.queryForList(
                    "SELECT * FROM leave_record WHERE user_id = ? AND deleted = 0 ORDER BY id DESC", userId);
            for (Map<String, Object> r : records) {
                System.out.println("RECORD: " + r.get("id") + " | Type: " + r.get("type") +
                        " | Days: " + r.get("days") + " | Expiry: " + r.get("expiry_date") +
                        " | Remarks: " + r.get("remarks"));
            }

            System.out.println("--- ACCOUNTS FOR USER " + userId + " ---");
            List<Map<String, Object>> accounts = jdbcTemplate.queryForList(
                    "SELECT * FROM leave_account WHERE user_id = ? AND deleted = 0", userId);
            for (Map<String, Object> a : accounts) {
                System.out.println("ACCOUNT: Year=" + a.get("year") + " | ActualQuota=" + a.get("actual_quota") +
                        " | LastYearBalance=" + a.get("last_year_balance"));
            }
        }
    }

    @Test
    public void testUser7Scenario() {
        System.out.println("🧪 Reproducing User 7 Scenario...");
        long userId = 77L; // Use a fresh ID for this scenario

        // 0. Setup: User has -1.0 debt in 2026, and 5.0 quota in 2027.
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        jdbcTemplate.update("DELETE FROM leave_account WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM leave_record WHERE user_id = ?", userId);

        // Insert into sys_user so initAllAccounts finds it
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password, deleted) VALUES (?, ?, ?, 0)", userId,
                "testuser" + userId, "pass");

        // 2026 Account and Debt
        jdbcTemplate.update(
                "INSERT INTO leave_account (user_id, year, actual_quota, last_year_balance, deleted) VALUES (?, 2026, 0, 0, 0)",
                userId);
        jdbcTemplate.update("INSERT INTO leave_record (user_id, type, days, start_date, end_date, remarks, deleted) " +
                "VALUES (?, 'ANNUAL', -1.0, '2026-06-01', '2026-06-01', '2026 Debt', 0)", userId);

        // 2027 Account Initialization (Pre-cleanup)
        System.out.println("⚙️ Initializing 2027 account...");
        scheduledTasks.initAllAccounts("2027");

        // Set 2027 quota to 5.0 (though my fix might now calculate it automatically if
        // entry date is set)
        // Let's set entry date for the user to ensure automatic calculation works
        jdbcTemplate.update("UPDATE sys_user SET entry_date = '2020-01-01' WHERE id = ?", userId);

        // 1. Run 2026 Cleanup (Target Expiry: 2026-12-31)
        System.out.println("🚀 Running cleanup for 2026...");
        scheduledTasks.cleanupExpiredLeaveBalances("2026");

        // 2. Check state
        System.out.println("--- STATE AFTER CLEANUP ---");
        printUserState(userId, 2026);

        // 3. Re-initialize/Refresh 2027 account
        System.out.println("⚙️ Refreshing 2027 account...");
        scheduledTasks.initAllAccounts("2027");

        System.out.println("--- FINAL STATE (2027 Account) ---");
        printUserState(userId, 2027);

        // 4. Verify DTO balances via LeaveService
        System.out.println("📊 Verifying DTO Balances:");
        com.leave.system.dto.LeaveAccountDTO dto2026 = leaveService.getAccount(userId, 2026);
        com.leave.system.dto.LeaveAccountDTO dto2027 = leaveService.getAccount(userId, 2027);

        System.out.println("  Year 2026 Total Balance: " + dto2026.getTotalBalance());
        System.out.println("  Year 2027 Total Balance: " + dto2027.getTotalBalance());
        System.out.println("  Year 2027 Last Year Balance: " + dto2027.getLastYearBalance());

        // 5. Explicit Cleanup Check (Testing the anchorTime logic)
        System.out.println("\n🧪 Testing Expiry Cleanup Usage Filtering:");
        jdbcTemplate.update("DELETE FROM leave_record WHERE user_id = ?", userId);

        LocalDate expiryDate = LocalDate.of(2026, 12, 31);
        LocalDateTime carrySnapshot = LocalDateTime.of(2026, 1, 1, 10, 0);

        // Carry over from 2025
        jdbcTemplate.update(
                "INSERT INTO leave_record (user_id, start_date, end_date, days, type, expiry_date, create_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                userId, "2026-01-01", "2026-01-01", 5.0, "CARRY_OVER", expiryDate, carrySnapshot);

        // Usage in 2025 (SHOULD BE IGNORED by 2026 cleanup because it's before
        // snapshot)
        jdbcTemplate.update(
                "INSERT INTO leave_record (user_id, start_date, end_date, days, type, expiry_date, create_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                userId, "2025-11-20", "2025-11-20", -1.0, "ANNUAL", expiryDate, LocalDateTime.of(2025, 11, 20, 10, 0));

        // Usage in 2026 (SHOULD BE INCLUDED)
        jdbcTemplate.update(
                "INSERT INTO leave_record (user_id, start_date, end_date, days, type, expiry_date, create_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                userId, "2026-02-15", "2026-02-15", -1.0, "ANNUAL", expiryDate, LocalDateTime.of(2026, 2, 15, 10, 0));

        scheduledTasks.cleanupExpiredLeaveBalances(String.valueOf(2026));

        List<Map<String, Object>> expiredRecords = jdbcTemplate.queryForList(
                "SELECT * FROM leave_record WHERE user_id = ? AND type = 'EXPIRED' AND start_date = '2026-12-31'",
                userId);

        if (!expiredRecords.isEmpty()) {
            BigDecimal expiredDays = (BigDecimal) expiredRecords.get(0).get("days");
            System.out.println("✅ Found EXPIRED record: " + expiredDays);
            if (expiredDays.compareTo(new BigDecimal("-4.0")) == 0) {
                System.out.println("💪 Usage filtering works! Only 2026 usage was subtracted.");
            } else {
                System.out.println("❌ Usage filtering failed. Expired days: " + expiredDays);
            }
        } else {
            System.out.println("❌ No EXPIRED record found after cleanup.");
        }
    }

    private void printUserState(long userId, int year) {
        System.out.println("--- User " + userId + " records for year " + year + " ---");
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "SELECT * FROM leave_record WHERE user_id = ? AND deleted = 0 AND YEAR(start_date) = ? ORDER BY id ASC",
                userId, year);
        for (Map<String, Object> r : records) {
            System.out.println("  RECORD: " + r.get("id") + " | Type: " + r.get("type") +
                    " | Days: " + r.get("days") + " | Expiry: " + r.get("expiry_date") +
                    " | Remarks: " + r.get("remarks"));
        }

        System.out.println("--- User " + userId + " account for " + year + " ---");
        List<Map<String, Object>> accounts = jdbcTemplate.queryForList(
                "SELECT * FROM leave_account WHERE user_id = ? AND deleted = 0 AND year = ?", userId, year);
        for (Map<String, Object> a : accounts) {
            System.out.println("  ACCOUNT: Year=" + a.get("year") + " | ActualQuota=" + a.get("actual_quota") +
                    " | LastYearBalance=" + a.get("last_year_balance"));
        }
    }

    @Test
    public void testUser5Scenario() {
        long userId = 5L;
        System.out.println("\n🧪 Testing User 5 Scenario (Potential Carry-Over Bug):");

        // 1. Cleanup
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        jdbcTemplate.update("DELETE FROM leave_account WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM leave_record WHERE user_id = ?", userId);

        // Insert user
        jdbcTemplate.update(
                "INSERT INTO sys_user (id, username, password, deleted, entry_date) VALUES (?, ?, ?, 0, '2020-01-01')",
                userId, "testuser5", "pass");

        // 2. Setup 2026 State
        // -1.0 Last Year Balance in 2026 Account
        jdbcTemplate.update(
                "INSERT INTO leave_account (user_id, year, last_year_balance, actual_quota, deleted) VALUES (?, ?, ?, ?, 0)",
                userId, 2026, -1.0, 0.0);

        // +2.0 Adjustment in 2026
        jdbcTemplate.update(
                "INSERT INTO leave_record (user_id, start_date, end_date, days, type, remarks, deleted) VALUES (?, ?, ?, ?, ?, ?, 0)",
                userId, "2026-06-01", "2026-06-01", 2.0, "ADJUSTMENT_ADD", "Manual increase");

        // 3. Run 2027 initialization
        System.out.println("⚙️ Initializing 2027 account for User 5...");
        scheduledTasks.initAllAccounts("2027");

        // 4. Verify
        com.leave.system.dto.LeaveAccountDTO dto2027 = leaveService.getAccount(userId, 2027);
        System.out.println("📊 User 5 2027 Account Stats:");
        System.out.println("  Last Year Balance: " + dto2027.getLastYearBalance());
        System.out.println("  Total Balance: " + dto2027.getTotalBalance());

        if (dto2027.getLastYearBalance().compareTo(new BigDecimal("1.0")) != 0) {
            System.out.println(
                    "❌ BUG REPRODUCED: Last year balance is " + dto2027.getLastYearBalance() + " but expected 1.0");
        } else {
            System.out.println("✅ Logic works as expected for this setup.");
        }
    }

    @Test
    public void test2026CarryOverScenarios() {
        System.out.println("\n🧪 Testing 2026 Carry-Over Scenarios (User 6 & Standard):");

        // --- Scenario 1: User 6 (Debt + Quota + Deduction) ---
        // 2025: Start -1.0, Quota 5.0, Adjustment -5.0 (without expiry)
        // Expect 2026 carry-over: -1.0
        Long user6Id = 6L;
        jdbcTemplate.execute("DELETE FROM leave_account WHERE user_id = " + user6Id);
        jdbcTemplate.execute("DELETE FROM leave_record WHERE user_id = " + user6Id);

        jdbcTemplate.execute(
                "INSERT INTO sys_user (id, username, password, real_name, status, deleted, first_work_date, entry_date) "
                        +
                        "VALUES (" + user6Id
                        + ", 'user6', 'pass', 'User 6', 'ACTIVE', 0, '2010-01-01', '2010-01-01') ON DUPLICATE KEY UPDATE status='ACTIVE'");

        // Insert 2025 account
        jdbcTemplate.execute(
                "INSERT INTO leave_account (user_id, year, actual_quota, last_year_balance, standard_quota, social_seniority, days_employed, deleted) "
                        +
                        "VALUES (" + user6Id + ", 2025, 5.0, -1.0, 5.0, 15, 365, 0)");

        // Insert -5.0 adjustment in 2025
        jdbcTemplate.execute(
                "INSERT INTO leave_record (user_id, start_date, end_date, days, type, remarks, create_time, expiry_date, deleted) "
                        +
                        "VALUES (" + user6Id
                        + ", '2025-06-01', '2025-06-01', -5.0, 'ADJUSTMENT_DEDUCT', 'Deduct 2025', NOW(), NULL, 0)");

        System.out.println("⚙️ Initializing 2026 account for User 6...");
        leaveService.initYearlyAccount(user6Id, 2026);
        printUserState(user6Id, 2026);

        // --- Scenario 2: Standard User (Carry-over positive balance) ---
        // 2025: Start 0.0, Quota 5.0, Usage -2.0
        // Expect 2026 carry-over: 3.0
        Long user8Id = 8L;
        jdbcTemplate.execute("DELETE FROM leave_account WHERE user_id = " + user8Id);
        jdbcTemplate.execute("DELETE FROM leave_record WHERE user_id = " + user8Id);
        jdbcTemplate.execute(
                "INSERT INTO sys_user (id, username, password, real_name, status, deleted, first_work_date, entry_date) "
                        +
                        "VALUES (" + user8Id
                        + ", 'user8', 'pass', 'User 8', 'ACTIVE', 0, '2020-01-01', '2020-01-01') ON DUPLICATE KEY UPDATE status='ACTIVE'");

        jdbcTemplate.execute(
                "INSERT INTO leave_account (user_id, year, actual_quota, last_year_balance, standard_quota, social_seniority, days_employed, deleted) "
                        +
                        "VALUES (" + user8Id + ", 2025, 5.0, 0.0, 5.0, 5, 365, 0)");

        jdbcTemplate.execute(
                "INSERT INTO leave_record (user_id, start_date, end_date, days, type, remarks, create_time, expiry_date, deleted) "
                        +
                        "VALUES (" + user8Id
                        + ", '2025-07-01', '2025-07-01', -2.0, 'ANNUAL', 'Use 2025', NOW(), '2025-12-31', 0)");

        System.out.println("\n⚙️ Initializing 2026 account for User 8...");
        leaveService.initYearlyAccount(user8Id, 2026);
    }

    @Test
    public void checkUser7Info() {
        long userId = 7L;
        System.out.println("\n🔍 Checking Floating Records for User 7:");
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "SELECT * FROM leave_record WHERE user_id = ? AND expiry_date IS NULL AND deleted = 0", userId);

        if (records.isEmpty()) {
            System.out.println("❌ No floating records found for User 7.");
        } else {
            for (Map<String, Object> r : records) {
                System.out.println("  ID: " + r.get("id") + " | Type: " + r.get("type") +
                        " | Days: " + r.get("days") + " | StartDate: " + r.get("start_date") +
                        " | Remarks: " + r.get("remarks"));
            }
        }

        System.out.println("\n📊 Current Account State (2025 & 2026):");
        printUserState(userId, 2025);
        printUserState(userId, 2026);

        System.out.println("\n🧪 Testing internal balance calculation for User 7 (applyLeave):");
        try {
            // Try to apply for 0.5 days (Should be less than 1.5 UI balance)
            leaveService.applyLeave(userId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1));
            System.out.println("✅ Applied for 1.0 day leave.");
            printUserState(userId, 2026);
        } catch (Exception e) {
            System.out.println("❌ Failed to apply for leave: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
