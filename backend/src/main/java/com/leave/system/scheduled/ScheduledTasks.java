package com.leave.system.scheduled;

import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.LeaveAccountMapper;
import com.leave.system.mapper.LeaveRecordMapper;
import com.leave.system.service.DingTalkService;
import com.leave.system.service.LeaveService;
import com.leave.system.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Scheduled tasks for leave management system
 */
@Component
public class ScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTasks.class);

    private final LeaveRecordMapper recordMapper;
    private final LeaveAccountMapper accountMapper;
    private final LeaveService leaveService;
    private final UserService userService;
    private final DingTalkService dingTalkService;

    /**
     * 自身代理引用。@Transactional 靠 Spring 代理生效, 直接 this.xxx() 调用会绕过代理,
     * REQUIRES_NEW 就不会真的开新事务。
     */
    @Autowired
    @Lazy
    private ScheduledTasks self;

    public ScheduledTasks(LeaveRecordMapper recordMapper,
            LeaveAccountMapper accountMapper,
            LeaveService leaveService,
            UserService userService,
            DingTalkService dingTalkService) {
        this.recordMapper = recordMapper;
        this.accountMapper = accountMapper;
        this.leaveService = leaveService;
        this.userService = userService;
        this.dingTalkService = dingTalkService;
    }

    /**
     * Cleanup expired leave balances
     * Runs every year on January 1
     * Defaults to cleaning up balances that expired on Dec 31 of last year
     *
     * <p>
     * 注意: 本方法刻意<b>不加 {@code @Transactional}</b>。它是一个编排方法, 内部会调用
     * 钉钉同步(含 HTTP 请求)以及逐用户的清理/初始化。若整体包在一个事务里:
     * 一是网络调用期间长时间持有数据库连接与行锁; 二是内层 {@code @Transactional}
     * 方法(如 applyLeave / initYearlyAccount)抛出异常并被上层 catch 掉之后, 共享事务
     * 已被标记为 rollback-only, 提交时抛 UnexpectedRollbackException, 导致
     * 「日志显示成功、数据全部回滚」。真正的事务边界下沉到单个用户。
     */
    public void cleanupExpiredLeaveBalances() {
        int lastYear = LocalDate.now().getYear() - 1;
        runYearEndRollover(lastYear);
    }

    /**
     * Manual trigger version of expiry cleanup
     *
     * @param year The year to clean balances for (e.g. "2024")
     */
    public void cleanupExpiredLeaveBalances(String year) {
        int targetYear;
        try {
            targetYear = Integer.parseInt(year.trim());
        } catch (RuntimeException e) {
            log.error("❌ Invalid year parameter for manual cleanup: {}", year);
            throw new IllegalArgumentException("清理年份参数非法: " + year);
        }
        runYearEndRollover(targetYear);
    }

    /**
     * 年终结转编排: 同步钉钉 -> 清理 cleanupYear 的过期额度 -> 初始化 cleanupYear+1 账户。
     * 三个阶段各自独立, 任一阶段失败不影响后续阶段已完成的数据。
     */
    private void runYearEndRollover(int cleanupYear) {
        log.info("🚀 Year-end rollover start: cleanup {} -> init {}", cleanupYear, cleanupYear + 1);

        List<String> problems = new ArrayList<>();

        log.info("🚀 Triggering DingTalk sync before expiry cleanup...");
        try {
            dingTalkService.syncLeaveData();
        } catch (Exception e) {
            log.error("❌ DingTalk sync failed, proceeding with cleanup anyway", e);
            problems.add("钉钉同步失败: " + e.getMessage());
        }

        // 清理阶段整体失败(取账户列表时数据库抖动等)也不能中断编排 ——
        // 第三步的账户初始化才是「全员新年度额度」的唯一来源, 它一旦被跳过,
        // 所有人整年都没有账户, 页面显示为空, 而且没有任何任务会补跑。
        try {
            int cleanupFailures = performCleanupForYear(cleanupYear);
            if (cleanupFailures > 0) {
                problems.add(cleanupYear + " 年过期清理有 " + cleanupFailures + " 人失败");
            }
        } catch (Exception e) {
            log.error("❌ Expiry cleanup for year {} aborted, proceeding to account init anyway", cleanupYear, e);
            problems.add(cleanupYear + " 年过期清理整体失败: " + e.getMessage());
        }

        log.info("🔄 Re-initializing all accounts for year {} to refresh carry-over balances...", cleanupYear + 1);
        int initFailures = initAllAccounts(String.valueOf(cleanupYear + 1));
        if (initFailures > 0) {
            problems.add((cleanupYear + 1) + " 年账户初始化有 " + initFailures + " 人失败");
        }

        if (!problems.isEmpty()) {
            // 抛出去, 让 SysJobServiceImpl 把失败原因写进 sys_job.last_run_result。
            // 年终结算一年只跑一次, 悄悄失败等于全员额度和结转都错到明年 ——
            // 必须让它在任务列表里显示为红色。本方法幂等, 修完可直接重跑。
            String summary = String.join("; ", problems);
            log.error("❌ Year-end rollover finished WITH PROBLEMS: {}", summary);
            throw new IllegalStateException("年终结算未完全成功 —— " + summary + " (修复后可重跑本任务, 幂等)");
        }

        log.info("✅ Year-end rollover finished: cleanup {} -> init {}", cleanupYear, cleanupYear + 1);
    }

    /**
     * Core logic for leave expiry cleanup
     * 
     * @param cleanupYear The year whose Dec 31 expiry balances should be cleaned
     */
    private int performCleanupForYear(int cleanupYear) {
        LocalDate targetExpiryDate = LocalDate.of(cleanupYear, 12, 31);

        log.info("🔄 Starting leave expiry cleanup for year: {} (Target Expiry: {})", cleanupYear, targetExpiryDate);

        // 1. Get all users who have an account for this year
        List<LeaveAccount> yearAccounts = accountMapper.selectAccountsByYear(cleanupYear);

        if (yearAccounts.isEmpty()) {
            log.info("No accounts found for year {}", cleanupYear);
            return 0;
        }

        int totalUsersAffected = 0;
        int failedUsers = 0;
        BigDecimal totalDaysExpired = BigDecimal.ZERO;

        for (LeaveAccount account : yearAccounts) {
            // 每个用户一个独立事务: 单个用户失败不影响其他用户, 也不会把整批标记为 rollback-only
            try {
                BigDecimal expired = self.cleanupUserForYear(account, targetExpiryDate);
                if (expired.compareTo(BigDecimal.ZERO) > 0) {
                    totalDaysExpired = totalDaysExpired.add(expired);
                    totalUsersAffected++;
                }
            } catch (Exception e) {
                failedUsers++;
                log.error("❌ Expiry cleanup failed for user {} (year {})", account.getUserId(), cleanupYear, e);
            }
        }

        if (failedUsers > 0) {
            log.error("⚠️  Expiry cleanup for year {}: {} user(s) FAILED and were skipped", cleanupYear, failedUsers);
        }
        log.info("✅ Expiry cleanup for year {} completed: {} users affected, {} total days expired, {} failed",
                cleanupYear, totalUsersAffected, totalDaysExpired, failedUsers);
        return failedUsers;
    }

    /**
     * 单个用户的过期清理, 独立事务。
     *
     * @return 本次实际过期作废的天数 (正数); 无过期则返回 0
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public BigDecimal cleanupUserForYear(LeaveAccount account, LocalDate targetExpiryDate) {
        Long userId = account.getUserId();
        int cleanupYear = targetExpiryDate.getYear();

        // 年终结算第一步: 按入职/离职日期算定该年度的在职天数与实际额度。
        // 当年额度是逐日累计的移动靶, 库里的值平时没有意义, 必须在这里算定成
        // 「这一年最终给了多少天」, 否则结转基数就是年中冻结的残值。
        // 顺带把还能被额度覆盖的历史透支归位。
        leaveService.settleYearQuota(userId, cleanupYear);
        LeaveAccount settled = accountMapper.selectAccountByUserIdAndYear(userId, cleanupYear);
        if (settled != null) {
            account = settled;
        }
        {
            // Find records for this user that expire on the target date (Bucket credits)
            List<LeaveRecord> userRecords = recordMapper.selectExpiringRecords(userId, targetExpiryDate);

            Optional<LeaveRecord> latestCarryOver = userRecords.stream()
                    .filter(r -> "CARRY_OVER".equals(r.getType()))
                    .max(Comparator.comparing(LeaveRecord::getCreateTime));

            final BigDecimal expiringBalance;

            // Restore snapshot logic: Use latest CARRY_OVER as base, add subsequent
            // adjustments
            if (latestCarryOver.isPresent()) {
                LeaveRecord carryOver = latestCarryOver.get();
                final LocalDateTime snapshotTime = carryOver.getCreateTime();
                BigDecimal baseBalance = carryOver.getDays();

                BigDecimal extraCredits = userRecords.stream()
                        .filter(r -> !"CARRY_OVER".equals(r.getType()))
                        .filter(r -> !r.getCreateTime().isBefore(snapshotTime))
                        .map(LeaveRecord::getDays)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                expiringBalance = baseBalance.add(extraCredits);
                log.debug("Using CARRY_OVER snapshot as base for user {}: {} + {} extra = {}",
                        userId, baseBalance, extraCredits, expiringBalance);
            } else {
                expiringBalance = userRecords.stream()
                        .map(LeaveRecord::getDays)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                log.debug("No CARRY_OVER found. Summing all credits for user {}: {}", userId, expiringBalance);
            }
            log.debug("Summing all credits for user {}: {}", userId, expiringBalance);

            // Calculate Implicit Carry Over logic (Account value > Sum of Records)
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

            // If a CARRY_OVER record exists, we only subtract usage that happened AFTER the
            // carry-over snapshot.
            // Usage that happened before the carry-over is already reflected in the
            // carry-over balance itself.
            LocalDateTime anchorTime = latestCarryOver.map(LeaveRecord::getCreateTime).orElse(null);

            // Pass anchorTime to include ONLY usage linked to this expiry bucket created
            // after the snapshot.
            List<LeaveRecord> usageRecords = recordMapper.selectUsageRecordsForExpiryCleanup(userId, targetExpiryDate,
                    anchorTime);
            BigDecimal totalUsed = usageRecords.stream()
                    .map(LeaveRecord::getDays)
                    .map(BigDecimal::abs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal remainingExpiring = finalExpiringBalance.subtract(totalUsed);

            List<LeaveRecord> alreadyExpiredRecords = recordMapper.selectExpiredRecordsByDate(userId, targetExpiryDate);

            BigDecimal alreadyExpiredAmount = alreadyExpiredRecords.stream()
                    .map(LeaveRecord::getDays)
                    .map(BigDecimal::abs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            remainingExpiring = remainingExpiring.subtract(alreadyExpiredAmount);

            List<LeaveRecord> floatingRecords = recordMapper.selectFloatingRecordsForCleanup(
                    userId, LocalDate.of(targetExpiryDate.getYear(), 1, 1), targetExpiryDate);

            BigDecimal currentNetDebt = floatingRecords.stream()
                    .map(LeaveRecord::getDays)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (currentNetDebt.compareTo(BigDecimal.ZERO) < 0) {
                BigDecimal debtToSettle = currentNetDebt.abs();

                if (remainingExpiring.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal offsetFromExpiring = remainingExpiring.min(debtToSettle);
                    log.info("💰 Debt Settlement (Tier 1): Settle {} days using EXPIRING bucket {} for user {}",
                            offsetFromExpiring, targetExpiryDate, userId);

                    LeaveRecord bucketDeduct = new LeaveRecord();
                    bucketDeduct.setUserId(userId);
                    bucketDeduct.setStartDate(targetExpiryDate);
                    bucketDeduct.setEndDate(targetExpiryDate);
                    bucketDeduct.setDays(offsetFromExpiring.negate());
                    bucketDeduct.setType("ADJUSTMENT_DEDUCT");
                    bucketDeduct.setExpiryDate(targetExpiryDate);
                    bucketDeduct.setRemarks("系统自动清理透支: 消耗过期额度 (" + targetExpiryDate + ")");
                    bucketDeduct.setDeleted(0);
                    bucketDeduct.setCreateTime(LocalDateTime.now());
                    recordMapper.insertRecord(bucketDeduct);

                    LeaveRecord debtOffset = new LeaveRecord();
                    debtOffset.setUserId(userId);
                    debtOffset.setStartDate(targetExpiryDate);
                    debtOffset.setEndDate(targetExpiryDate);
                    debtOffset.setDays(offsetFromExpiring);
                    debtOffset.setType("ADJUSTMENT_ADD");
                    debtOffset.setRemarks("系统自动清理透支: 冲抵历史欠费 (来源: " + targetExpiryDate + ")");
                    debtOffset.setDeleted(0);
                    debtOffset.setCreateTime(LocalDateTime.now());
                    recordMapper.insertRecord(debtOffset);

                    debtToSettle = debtToSettle.subtract(offsetFromExpiring);
                    remainingExpiring = remainingExpiring.subtract(offsetFromExpiring);
                }

                // Tier 2 and 3 removed: Debt is now carried over as negative
                // 'last_year_balance'
                // in LeaveServiceImpl.calculateCarryOverBalance, as per user requirement.
            }

            if (remainingExpiring.compareTo(BigDecimal.ZERO) > 0) {
                LeaveRecord expiredRecord = new LeaveRecord();
                expiredRecord.setUserId(userId);
                expiredRecord.setStartDate(targetExpiryDate);
                expiredRecord.setEndDate(targetExpiryDate);
                expiredRecord.setDays(remainingExpiring.negate());
                expiredRecord.setType("EXPIRED");
                expiredRecord.setExpiryDate(targetExpiryDate);
                expiredRecord.setRemarks("年假已过期自动清理 (到期日期: " + targetExpiryDate + ")");
                expiredRecord.setDeleted(0);
                expiredRecord.setCreateTime(LocalDateTime.now());
                recordMapper.insertRecord(expiredRecord);

                log.info("⏱️  Expired {} days for user {} (target expiry date: {})",
                        remainingExpiring, userId, targetExpiryDate);
                return remainingExpiring;
            }
        }
        return BigDecimal.ZERO;
    }

    public void initAllAccounts() {
        initAllAccounts(null);
    }

    /** @return 初始化失败的用户数 */
    public int initAllAccounts(String year) {
        Integer targetYear;
        if (year == null || year.trim().isEmpty() || "DEFAULT".equalsIgnoreCase(year)) {
            targetYear = LocalDate.now().getYear();
            log.info("ℹ️ No year specified, defaulting to current year: {}", targetYear);
        } else {
            try {
                targetYear = Integer.parseInt(year);
            } catch (NumberFormatException e) {
                log.error("Invalid year parameter: {}", year);
                return 0;
            }
        }

        log.info("🔄 Starting batch account initialization for year: {}", targetYear);

        int successCount = 0;
        int failCount = 0;

        try {
            List<SysUser> users = userService.getAllUsers();

            for (SysUser user : users) {
                try {
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
            failCount++;
        }
        return failCount;
    }

}
