package com.leave.system.scheduled;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.LeaveAccount;
import com.leave.system.mapper.LeaveRecordMapper;
import com.leave.system.mapper.LeaveAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scheduled tasks for leave management system
 */
@Component
public class ScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTasks.class);

    private final LeaveRecordMapper recordMapper;
    private final LeaveAccountMapper accountMapper;
    private final com.leave.system.service.LeaveService leaveService;

    public ScheduledTasks(LeaveRecordMapper recordMapper,
            LeaveAccountMapper accountMapper,
            com.leave.system.service.LeaveService leaveService) {
        this.recordMapper = recordMapper;
        this.accountMapper = accountMapper;
        this.leaveService = leaveService;
    }

    /**
     * Cleanup expired leave balances
     * Runs every year on January 1 at 3:00 AM
     * Defaults to cleaning up balances that expired on Dec 31 of last year
     */
    @Transactional(rollbackFor = Exception.class)
    public void cleanupExpiredLeaveBalances() {
        int lastYear = LocalDate.now().getYear() - 1;
        performCleanupForYear(lastYear);
    }

    /**
     * Manual trigger version of expiry cleanup
     * 
     * @param year The year to cleanup balances for (e.g. "2024")
     */
    @Transactional(rollbackFor = Exception.class)
    public void cleanupExpiredLeaveBalances(String year) {
        try {
            int targetYear = Integer.parseInt(year);
            performCleanupForYear(targetYear);
        } catch (NumberFormatException e) {
            log.error("❌ Invalid year parameter for manual cleanup: {}", year);
        }
    }

    /**
     * Core logic for leave expiry cleanup
     * 
     * @param cleanupYear The year whose Dec 31 expiry balances should be cleaned
     */
    private void performCleanupForYear(int cleanupYear) {
        LocalDate targetExpiryDate = LocalDate.of(cleanupYear, 12, 31);

        log.info("🔄 Starting leave expiry cleanup for year: {} (Target Expiry: {})", cleanupYear, targetExpiryDate);

        // 1. Get all users who have an account for this year
        // We must process ALL active users because they might have 'NULL' expiry debt
        // that needs offsetting even if they have no bucketed balance.
        List<LeaveAccount> yearAccounts = accountMapper.selectList(
                new QueryWrapper<LeaveAccount>()
                        .eq("year", cleanupYear)
                        .eq("deleted", 0));

        if (yearAccounts.isEmpty()) {
            log.info("No accounts found for year {}", cleanupYear);
            return;
        }

        int totalUsersAffected = 0;
        BigDecimal totalDaysExpired = BigDecimal.ZERO;

        for (LeaveAccount account : yearAccounts) {
            Long userId = account.getUserId();

            // Find records for this user that expire on the target date (Bucket credits)
            List<LeaveRecord> userRecords = recordMapper.selectList(
                    new QueryWrapper<LeaveRecord>()
                            .eq("user_id", userId)
                            .eq("expiry_date", targetExpiryDate)
                            .in("type", Arrays.asList("ADJUSTMENT_ADD", "CARRY_OVER"))
                            .eq("deleted", 0));

            // A shortcut: if user has no bucketed balance and no pending debt, skip
            // actually no, we'll check properly.

            // 1. Calculate credit balance for this specific expiry date (The "Expiring
            // Bucket")
            // Priority logic: If CARRY_OVER exists, use it as the base balance (snapshot).
            // Any ADJUSTMENT_ADD created BEFORE the latest CARRY_OVER is already included
            // in it.
            Optional<LeaveRecord> latestCarryOver = userRecords.stream()
                    .filter(r -> "CARRY_OVER".equals(r.getType()))
                    .max(Comparator.comparing(LeaveRecord::getCreateTime));

            final BigDecimal expiringBalance;
            final LocalDateTime anchorTime;

            if (latestCarryOver.isPresent()) {
                LeaveRecord carryOver = latestCarryOver.get();
                final LocalDateTime snapshotTime = carryOver.getCreateTime();
                anchorTime = snapshotTime;
                BigDecimal baseBalance = carryOver.getDays();

                // Extra credits created AFTER the carry-over snapshot
                BigDecimal extraCredits = userRecords.stream()
                        .filter(r -> !"CARRY_OVER".equals(r.getType()))
                        .filter(r -> r.getCreateTime().isAfter(snapshotTime))
                        .map(LeaveRecord::getDays)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                expiringBalance = baseBalance.add(extraCredits);
                log.debug("Using CARRY_OVER snapshot as base for user {}: {} + {} extra = {}",
                        userId, baseBalance, extraCredits, expiringBalance);
            } else {
                anchorTime = null;
                expiringBalance = userRecords.stream()
                        .map(LeaveRecord::getDays)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                log.debug("No CARRY_OVER found. Summing all credits for user {}: {}", userId, expiringBalance);
            }
            // 2. Account for IMPLICIT Carry Over (Manually edited in Account but no Record)
            // We compare account.lastYearBalance against the recorded CARRY_OVER sum.
            BigDecimal recordedCarryOverSum = userRecords.stream()
                    .filter(r -> "CARRY_OVER".equals(r.getType()))
                    .map(LeaveRecord::getDays)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal accountLastYearBalance = account.getLastYearBalance() != null ? account.getLastYearBalance()
                    : BigDecimal.ZERO;

            BigDecimal finalExpiringBalance = expiringBalance;
            if (accountLastYearBalance.compareTo(recordedCarryOverSum) > 0) {
                BigDecimal implicitDiff = accountLastYearBalance.subtract(recordedCarryOverSum);
                finalExpiringBalance = expiringBalance.add(implicitDiff);
                log.info("ℹ️ Adding implicit carry-over diff for user {}: {}", userId, implicitDiff);
            }

            // 3. Separate "Protection Balance" (actualQuota - expires NEXT year)
            BigDecimal protectionBalance = BigDecimal.ZERO;
            if (targetExpiryDate.getYear() == account.getYear() && account.getActualQuota() != null) {
                protectionBalance = account.getActualQuota();
            }

            // 4. Calculate usage records targeting the SAME expiry date
            QueryWrapper<LeaveRecord> usageWrapper = new QueryWrapper<LeaveRecord>()
                    .eq("user_id", userId)
                    .in("type", Arrays.asList("ANNUAL", "ADJUSTMENT_DEDUCT"))
                    .eq("expiry_date", targetExpiryDate)
                    .eq("deleted", 0);

            if (anchorTime != null) {
                usageWrapper.gt("create_time", anchorTime);
            }

            List<LeaveRecord> usageRecords = recordMapper.selectList(usageWrapper);
            BigDecimal totalUsed = usageRecords.stream()
                    .map(LeaveRecord::getDays)
                    .map(BigDecimal::abs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Remaining expiring balance after usage
            BigDecimal remainingExpiring = finalExpiringBalance.subtract(totalUsed);

            // 4. Idempotency Check: Subtract already EXPIRED records for this specific
            // target
            List<LeaveRecord> alreadyExpiredRecords = recordMapper.selectList(
                    new QueryWrapper<LeaveRecord>()
                            .eq("user_id", userId)
                            .eq("type", "EXPIRED")
                            .eq("start_date", targetExpiryDate)
                            .eq("deleted", 0));

            BigDecimal alreadyExpiredAmount = alreadyExpiredRecords.stream()
                    .map(LeaveRecord::getDays)
                    .map(BigDecimal::abs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            remainingExpiring = remainingExpiring.subtract(alreadyExpiredAmount);

            // 5. DEBT OFFSETTING (Tiered)
            // Get current net debt (global floating pool)
            List<LeaveRecord> floatingRecords = recordMapper.selectList(
                    new QueryWrapper<LeaveRecord>()
                            .eq("user_id", userId)
                            .isNull("expiry_date")
                            .ne("type", "CARRY_OVER")
                            .eq("deleted", 0));

            BigDecimal currentNetDebt = floatingRecords.stream()
                    .map(LeaveRecord::getDays)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (currentNetDebt.compareTo(BigDecimal.ZERO) < 0) {
                BigDecimal debtToSettle = currentNetDebt.abs();

                // Tier 1: Offset from EXPIRING balance
                if (remainingExpiring.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal offsetFromExpiring = remainingExpiring.min(debtToSettle);
                    log.info("💰 Debt Settlement (Tier 1): Settle {} days using EXPIRING bucket {} for user {}",
                            offsetFromExpiring, targetExpiryDate, userId);

                    // Record 1: Consume expiring bucket
                    LeaveRecord bucketDeduct = new LeaveRecord();
                    bucketDeduct.setUserId(userId);
                    bucketDeduct.setStartDate(targetExpiryDate);
                    bucketDeduct.setEndDate(targetExpiryDate);
                    bucketDeduct.setDays(offsetFromExpiring.negate());
                    bucketDeduct.setType("ADJUSTMENT_DEDUCT");
                    bucketDeduct.setExpiryDate(targetExpiryDate);
                    bucketDeduct.setRemarks("系统自动清理透支: 消耗过期额度 (" + targetExpiryDate + ")");
                    recordMapper.insert(bucketDeduct);

                    // Record 2: Offset global debt
                    LeaveRecord debtOffset = new LeaveRecord();
                    debtOffset.setUserId(userId);
                    debtOffset.setStartDate(targetExpiryDate);
                    debtOffset.setEndDate(targetExpiryDate);
                    debtOffset.setDays(offsetFromExpiring);
                    debtOffset.setType("ADJUSTMENT_ADD");
                    debtOffset.setRemarks("系统自动清理透支: 冲抵历史欠费 (来源: " + targetExpiryDate + ")");
                    recordMapper.insert(debtOffset);

                    debtToSettle = debtToSettle.subtract(offsetFromExpiring);
                    remainingExpiring = remainingExpiring.subtract(offsetFromExpiring);
                }

                // Tier 2: Offset from PROTECTION bucket (Actual Quota)
                if (debtToSettle.compareTo(BigDecimal.ZERO) > 0 && protectionBalance.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal offsetFromProtection = protectionBalance.min(debtToSettle);
                    LocalDate nextYearExpiry = LocalDate.of(targetExpiryDate.getYear() + 1, 12, 31);

                    log.info(
                            "💰 Debt Settlement (Tier 2): Settle {} days using PROTECTION bucket (expires {}) for user {}",
                            offsetFromProtection, nextYearExpiry, userId);

                    // Record 1: Consume protection bucket (Match next year expiry)
                    LeaveRecord bucketDeduct = new LeaveRecord();
                    bucketDeduct.setUserId(userId);
                    bucketDeduct.setStartDate(targetExpiryDate);
                    bucketDeduct.setEndDate(targetExpiryDate);
                    bucketDeduct.setDays(offsetFromProtection.negate());
                    bucketDeduct.setType("ADJUSTMENT_DEDUCT");
                    bucketDeduct.setExpiryDate(nextYearExpiry); // IMPORTANT: Protect the quota's original expiry
                    bucketDeduct.setRemarks("系统自动清理透支: 消耗当年配额 (过期: " + nextYearExpiry + ")");
                    recordMapper.insert(bucketDeduct);

                    // Record 2: Offset global debt
                    LeaveRecord debtOffset = new LeaveRecord();
                    debtOffset.setUserId(userId);
                    debtOffset.setStartDate(targetExpiryDate);
                    debtOffset.setEndDate(targetExpiryDate);
                    debtOffset.setDays(offsetFromProtection);
                    debtOffset.setType("ADJUSTMENT_ADD");
                    debtOffset.setRemarks("系统自动清理透支: 冲抵历史欠费 (来源: 当年配额)");
                    recordMapper.insert(debtOffset);

                    debtToSettle = debtToSettle.subtract(offsetFromProtection);
                    // protectiveBalance doesn't need updating here as we aren't clearing it
                }
            }

            // 6. FINAL EXPIRY: Only clear remaining expiring balance
            if (remainingExpiring.compareTo(BigDecimal.ZERO) > 0) {
                LeaveRecord expiredRecord = new LeaveRecord();
                expiredRecord.setUserId(userId);
                expiredRecord.setStartDate(targetExpiryDate);
                expiredRecord.setEndDate(targetExpiryDate);
                expiredRecord.setDays(remainingExpiring.negate());
                expiredRecord.setType("EXPIRED");
                expiredRecord.setExpiryDate(targetExpiryDate);
                expiredRecord.setRemarks("年假已过期自动清理 (到期日期: " + targetExpiryDate + ")");
                recordMapper.insert(expiredRecord);

                totalDaysExpired = totalDaysExpired.add(remainingExpiring);
                totalUsersAffected++;
                log.info("⏱️  Expired {} days for user {} (target expiry date: {})",
                        remainingExpiring, userId, targetExpiryDate);
            }
        }

        log.info("✅ Expiry cleanup for year {} completed: {} users affected, {} total days expired",
                cleanupYear, totalUsersAffected, totalDaysExpired);
    }

    /**
     * Batch initialize all accounts for the current year (default behavior)
     */
    public void initAllAccounts() {
        initAllAccounts(null);
    }

    /**
     * Batch initialize all accounts for a specific year
     * Can be manually triggered from job system
     * 
     * @param year Target year (as String). If null or empty, defaults to current
     *             year.
     */
    public void initAllAccounts(String year) {
        Integer targetYear;
        if (year == null || year.trim().isEmpty() || "DEFAULT".equalsIgnoreCase(year)) {
            targetYear = LocalDate.now().getYear();
            log.info("ℹ️ No year specified, defaulting to current year: {}", targetYear);
        } else {
            try {
                targetYear = Integer.parseInt(year);
            } catch (NumberFormatException e) {
                log.error("Invalid year parameter: {}", year);
                return;
            }
        }

        log.info("🔄 Starting batch account initialization for year: {}", targetYear);

        int successCount = 0;
        int failCount = 0;

        try {
            List<com.leave.system.entity.SysUser> users = leaveService.getAllUsers();

            for (com.leave.system.entity.SysUser user : users) {
                try {
                    // Skip resigned users
                    if ("RESIGNED".equals(user.getStatus())) {
                        continue;
                    }

                    leaveService.initYearlyAccount(user.getId(), targetYear);
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    log.error("Failed to init account for user {}: {}", user.getId(), e.getMessage());
                }
            }

            log.info("✅ Batch initialization completed: {} success, {} failed out of {} total users",
                    successCount, failCount, users.size());
        } catch (Exception e) {
            log.error("❌ Batch initialization failed", e);
        }
    }

}
