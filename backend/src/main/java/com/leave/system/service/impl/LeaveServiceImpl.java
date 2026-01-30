package com.leave.system.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leave.system.dto.LeaveAccountDTO;
import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.exception.BusinessException;
import com.leave.system.mapper.LeaveAccountMapper;
import com.leave.system.mapper.LeaveRecordMapper;
import com.leave.system.mapper.SysJobMapper;
import com.leave.system.mapper.SysUserMapper;
import com.leave.system.service.LeaveService;
import com.leave.system.entity.SysJob;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LeaveServiceImpl implements LeaveService {

    private static final Logger log = LoggerFactory.getLogger(LeaveServiceImpl.class);

    private final LeaveAccountMapper accountMapper;
    private final LeaveRecordMapper recordMapper;
    private final SysUserMapper userMapper;
    private final SysJobMapper jobMapper;

    public LeaveServiceImpl(LeaveAccountMapper accountMapper, LeaveRecordMapper recordMapper,
            SysUserMapper userMapper, SysJobMapper jobMapper) {
        this.accountMapper = accountMapper;
        this.recordMapper = recordMapper;
        this.userMapper = userMapper;
        this.jobMapper = jobMapper;
    }

    /**
     * Calculate carry-over balance excluding expired leave
     * 
     * @param userId User ID
     * @param year   Target year (e.g., 2025 when initializing for 2025)
     * @return Carry-over days (non-expired balance from previous year)
     */
    private BigDecimal calculateCarryOverBalance(Long userId, Integer year) {
        int lastYear = year - 1;

        log.info("🔍 Calculating carry-over for user {} from year {} to {}", userId, lastYear, year);

        LeaveAccount lastYearAccount = accountMapper.selectLastYearAccount(userId, year);

        if (lastYearAccount == null) {
            log.info("❌ No account found for year {}, cannot carry over", lastYear);
            return BigDecimal.ZERO;
        }

        // 1. Group balances by expiry date
        Map<LocalDate, BigDecimal> balanceByExpiry = new HashMap<>();

        // Quota from last year (e.g. 2025 quota) expires at the end of THIS year (2026)
        // (2-year validity policy)
        LocalDate quotaExpiry = LocalDate.of(year, 12, 31);
        BigDecimal lastYearQuota = lastYearAccount.getActualQuota() != null ? lastYearAccount.getActualQuota()
                : BigDecimal.ZERO;
        balanceByExpiry.merge(quotaExpiry, lastYearQuota, BigDecimal::add);

        // Balance brought forward TO last year (e.g. 2024 -> 2025) expires at the end
        // of LAST year (2025)
        LocalDate carriedForwardExpiry = LocalDate.of(lastYear, 12, 31);
        BigDecimal lastYearBroughtForward = lastYearAccount.getLastYearBalance() != null
                ? lastYearAccount.getLastYearBalance()
                : BigDecimal.ZERO;
        balanceByExpiry.merge(carriedForwardExpiry, lastYearBroughtForward, BigDecimal::add);

        log.info("📊 Last year base: Quota {} (expires {}), BroughtForward {} (expires {})",
                lastYearQuota, quotaExpiry, lastYearBroughtForward, carriedForwardExpiry);

        // 2. Add all records from last year to their respective buckets
        List<LeaveRecord> lastYearRecords = recordMapper.selectRecordsForCarryOver(userId, lastYear);
        log.info("📋 Found {} records in year {}", lastYearRecords.size(), lastYear);

        for (LeaveRecord record : lastYearRecords) {
            if ("EXPIRED".equals(record.getType()) || "CARRY_OVER".equals(record.getType())) {
                continue;
            }
            LocalDate recordExpiry = record.getExpiryDate();
            BigDecimal days = record.getDays() != null ? record.getDays() : BigDecimal.ZERO;
            balanceByExpiry.merge(recordExpiry, days, BigDecimal::add);
        }

        // 3. Robust Offset: Deduct all usage (negative) from available credits
        // (positive)
        // prioritized by earliest expiry date.

        // Split into Positives (Credits) and total negative (Debt/Usage)
        Map<LocalDate, BigDecimal> positiveBuckets = new HashMap<>();
        BigDecimal totalDebt = BigDecimal.ZERO;

        for (Map.Entry<LocalDate, BigDecimal> entry : balanceByExpiry.entrySet()) {
            BigDecimal val = entry.getValue();
            if (val.compareTo(BigDecimal.ZERO) >= 0) {
                positiveBuckets.put(entry.getKey(), val);
            } else {
                totalDebt = totalDebt.add(val.abs());
            }
        }

        if (totalDebt.compareTo(BigDecimal.ZERO) > 0) {
            log.info("⚖️ Offsetting total debt of {} days against available credits", totalDebt);
            // Sort positive buckets by expiry (earliest first, NULL/Floating comes LAST)
            List<LocalDate> sortedExpiryDates = new ArrayList<>(positiveBuckets.keySet());
            sortedExpiryDates.sort((d1, d2) -> {
                if (d1 == null)
                    return 1;
                if (d2 == null)
                    return -1;
                return d1.compareTo(d2);
            });

            for (LocalDate expiry : sortedExpiryDates) {
                BigDecimal credit = positiveBuckets.get(expiry);
                BigDecimal offset = credit.min(totalDebt);
                positiveBuckets.put(expiry, credit.subtract(offset));
                totalDebt = totalDebt.subtract(offset);
                if (totalDebt.compareTo(BigDecimal.ZERO) <= 0)
                    break;
            }
        }

        // 4. Determine final carry-over after expiry
        LocalDate jan1NextYear = LocalDate.of(year, 1, 1);
        BigDecimal finalCarryOver = BigDecimal.ZERO;

        // Sum remaining credits (only if not expired)
        for (Map.Entry<LocalDate, BigDecimal> entry : positiveBuckets.entrySet()) {
            LocalDate expiry = entry.getKey();
            BigDecimal credit = entry.getValue();

            if (expiry != null && expiry.isBefore(jan1NextYear)) {
                log.info("  ⏭️  Expired credit from bucket {}: {} days", expiry, credit);
            } else {
                log.info("  ➕ Carried over credit from bucket {}: {} days",
                        expiry == null ? "NULL" : expiry, credit);
                finalCarryOver = finalCarryOver.add(credit);
            }
        }

        // Subtract remaining debt (Debt never expires)
        if (totalDebt.compareTo(BigDecimal.ZERO) > 0) {
            log.info("  ⚠️  Carrying over remaining DEBT: -{} days", totalDebt);
            finalCarryOver = finalCarryOver.subtract(totalDebt);
        }

        log.info("💰 Final carry-over for user {} year {}: {} days", userId, year, finalCarryOver);
        return finalCarryOver;
    }

    /**
     * Calculate days employed in a specific year
     */
    private int calculateDaysEmployed(LocalDate entryDate, int year) {
        if (entryDate == null) {
            // For future years, return 0
            if (year > LocalDate.now().getYear()) {
                return 0;
            }
            // If no entry date, assume employed for the full year (past/current)
            return LocalDate.of(year, 12, 31).getDayOfYear();
        }
        LocalDate startOfYear = LocalDate.of(year, 1, 1);
        LocalDate endOfYear = LocalDate.of(year, 12, 31);
        LocalDate today = LocalDate.now();

        // If year is in the future, return 0 (User requirement: strictly by days
        // employed)
        if (year > today.getYear()) {
            return 0;
        }

        // If calculating for current year, cap at today
        if (year == today.getYear()) {
            endOfYear = today;
        }

        if (entryDate.isAfter(endOfYear)) {
            return 0;
        }

        LocalDate effectiveStartDate = entryDate.isBefore(startOfYear) ? startOfYear : entryDate;
        // If effective start date is after end date (e.g. future entry date), return 0
        if (effectiveStartDate.isAfter(endOfYear)) {
            return 0;
        }

        return (int) ChronoUnit.DAYS.between(effectiveStartDate, endOfYear) + 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LeaveAccount initYearlyAccount(Long userId, Integer year) {
        SysUser user = userMapper.selectUserById(userId);
        if (user == null) {
            throw new BusinessException("User not found");
        }
        return refreshAccount(user, year);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LeaveAccount refreshAccount(SysUser user, Integer year) {
        // 1. Calculate seniority as of today (Previous Logic)
        int seniority = 0;
        if (user.getFirstWorkDate() != null) {
            java.time.Period period = java.time.Period.between(user.getFirstWorkDate(), LocalDate.now());
            seniority = period.getYears();
        }

        // 2. Company Policy: <10 yr: 5, 10-20 yr: 10, >=20 yr: 15
        BigDecimal standardQuota = getQuotaBySeniority(seniority);

        // 3. Calculate Days Employed in this year
        int daysEmployed = calculateDaysEmployed(user.getEntryDate(), year);

        // 4. Calculate Actual Quota based on days employed
        // Formula: standardQuota * (daysEmployed / daysInYear)
        BigDecimal daysInYear = new BigDecimal(LocalDate.of(year, 12, 31).getDayOfYear());
        BigDecimal rawQuota = standardQuota
                .multiply(new BigDecimal(daysEmployed))
                .divide(daysInYear, 10, RoundingMode.HALF_UP);

        // Round down to nearest 0.5
        BigDecimal actualQuota = rawQuota.multiply(new BigDecimal("2"))
                .setScale(0, RoundingMode.FLOOR)
                .divide(new BigDecimal("2"), 1, RoundingMode.FLOOR);

        return updateOrCreateAccount(user, year, standardQuota, actualQuota, daysEmployed, seniority);
    }

    private BigDecimal getQuotaBySeniority(int seniority) {
        if (seniority < 10) {
            return new BigDecimal("5.0");
        } else if (seniority < 20) {
            return new BigDecimal("10.0");
        } else {
            return new BigDecimal("15.0");
        }
    }

    /**
     * Extracted logic for updating/creating the account record
     */
    private LeaveAccount updateOrCreateAccount(SysUser user, Integer year, BigDecimal standardQuota,
            BigDecimal actualQuota, int daysEmployed, int seniority) {
        Long userId = user.getId();
        // Calculate carry over from last year (excluding expired balances)
        BigDecimal carryOverBalance = BigDecimal.ZERO;
        LeaveAccount lastYearAccount = accountMapper.selectLastYearAccount(userId, year);

        if (lastYearAccount != null) {
            // Use new calculation method that considers expiry dates
            carryOverBalance = calculateCarryOverBalance(userId, year);

            // Create or Update CARRY_OVER record
            LocalDate startOfYear = LocalDate.of(year, 1, 1);
            LeaveRecord carryRecord = recordMapper.selectCarryOverRecord(userId, startOfYear);
            LocalDate expiryDate = LocalDate.of(year, 12, 31);

            if (carryRecord != null) {
                carryRecord.setDays(carryOverBalance);
                carryRecord.setRemarks(String.format("上年结余年假结转 (过期: %s)", expiryDate));
                carryRecord.setExpiryDate(expiryDate);
                recordMapper.updateRecord(carryRecord);
            } else {
                carryRecord = new LeaveRecord();
                carryRecord.setUserId(userId);
                carryRecord.setStartDate(startOfYear);
                carryRecord.setEndDate(startOfYear);
                carryRecord.setDays(carryOverBalance);
                carryRecord.setType("CARRY_OVER");
                carryRecord.setRemarks(String.format("上年结余年假结转 (过期: %s)", expiryDate));
                carryRecord.setExpiryDate(expiryDate);
                carryRecord.setCreateTime(LocalDateTime.now());
                recordMapper.insertRecord(carryRecord);
            }
        }

        LeaveAccount account = accountMapper.selectAccountByUserIdAndYearIncludeDeleted(userId, year);
        if (account == null) {
            account = new LeaveAccount();
            account.setUserId(userId);
            account.setYear(year);
            account.setSocialSeniority(seniority);
            account.setStandardQuota(standardQuota);
            account.setActualQuota(actualQuota);
            account.setLastYearBalance(carryOverBalance);
            account.setCurrentYearUsed(BigDecimal.ZERO);
            account.setDaysEmployed(daysEmployed);
            account.setDeleted(0);
            accountMapper.insertAccount(account);
        } else {
            if (account.getDeleted() != null && account.getDeleted() == 1) {
                account.setDeleted(0);
            }
            account.setSocialSeniority(seniority);
            account.setStandardQuota(standardQuota);
            account.setActualQuota(actualQuota);
            account.setDaysEmployed(daysEmployed);
            if (lastYearAccount != null) {
                account.setLastYearBalance(carryOverBalance);
            }
            accountMapper.updateAccount(account);
        }
        return account;
    }

    /**
     * Dynamically refresh account quota if it's the current year
     * This ensures daysEmployed and actualQuota are always up to date with
     * LocalDate.now()
     */
    private void refreshCurrentYearAccount(LeaveAccount account, SysUser user) {
        int year = account.getYear();
        LocalDate today = LocalDate.now();

        // Only refresh for current year
        if (year != today.getYear()) {
            return;
        }

        log.debug("🔄 Refreshing account for user {} year {} based on today {}", user.getId(), year, today);

        // Calculate Days Employed in this year (up to today)
        int daysEmployed = calculateDaysEmployed(user.getEntryDate(), year);

        // If days employed hasn't changed, no need to recalc everything (optimization)
        /*
         * Skipping optimization to ensure strictly consistent calculation logic in case
         * formula changes.
         * But effectively, raw DB update is cheap enough for single user.
         */

        // Recalculate Quota
        BigDecimal standardQuota = account.getStandardQuota(); // Assume standard quota (seniority tier) is stable for
                                                               // the year
        // Re-calibrating seniority mid-year?
        // Seniority usually fixed at year start or calc dynamically?
        // Current initYearlyAccount calculates seniority at that moment.
        // For now, assume standardQuota is stable or re-calc if needed.
        // Let's rely on stored standardQuota but re-calc the pro-rated part.

        if (standardQuota == null) {
            standardQuota = BigDecimal.ZERO;
        }

        BigDecimal daysInYear = new BigDecimal(LocalDate.of(year, 12, 31).getDayOfYear());
        BigDecimal rawQuota = standardQuota
                .multiply(new BigDecimal(daysEmployed))
                .divide(daysInYear, 10, RoundingMode.HALF_UP);

        // Round down to nearest 0.5
        BigDecimal actualQuota = rawQuota.multiply(new BigDecimal("2"))
                .setScale(0, RoundingMode.FLOOR)
                .divide(new BigDecimal("2"), 1, RoundingMode.FLOOR);

        // Check if values changed
        boolean changed = false;
        if (account.getDaysEmployed() == null || account.getDaysEmployed() != daysEmployed) {
            account.setDaysEmployed(daysEmployed);
            changed = true;
        }
        if (account.getActualQuota() == null || account.getActualQuota().compareTo(actualQuota) != 0) {
            account.setActualQuota(actualQuota);
            changed = true;
        }

        if (changed) {
            accountMapper.updateAccount(account);
            log.info("✅ Refreshed dynamic quota for user {}: employed={} days, quota={}",
                    user.getId(), daysEmployed, actualQuota);
        }
    }

    @Override
    @Transactional
    public void applyLeave(Long userId, LocalDate startDate, LocalDate endDate) {
        // Calculate days requested based on date range (default behavior)
        long daysDiff = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        applyLeave(userId, startDate, endDate, new BigDecimal(daysDiff));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyLeave(Long userId, LocalDate startDate, LocalDate endDate, BigDecimal daysRequested) {
        int year = startDate.getYear();

        // Ensure account exists
        LeaveAccount account = accountMapper.selectAccountByUserIdAndYear(userId, year);
        if (account == null) {
            initYearlyAccount(userId, year);
            account = accountMapper.selectAccountByUserIdAndYear(userId, year);
        }

        if (daysRequested.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Invalid duration: " + daysRequested);
        }

        log.info("📝 Processing leave application: user={}, dates={} to {}, days={}",
                userId, startDate, endDate, daysRequested);

        deductLeaveDays(userId, daysRequested, startDate, endDate, "ANNUAL", "员工请假");

        log.info("✅ Leave application processed successfully");
    }

    /**
     * Core logic to deduct leave days from available balances with priority
     */
    private void deductLeaveDays(Long userId, BigDecimal daysToDeduct, LocalDate startDate, LocalDate endDate,
            String type, String remarksPrefix) {
        int year = startDate.getYear();

        // Ensure account exists or init it (needed for quota info)
        LeaveAccount account = accountMapper.selectAccountByUserIdAndYear(userId, year);
        if (account == null) {
            initYearlyAccount(userId, year);
            account = accountMapper.selectAccountByUserIdAndYear(userId, year);
        }

        // Ensure quota is fresh for current year before deduction checks
        if (year == LocalDate.now().getYear()) {
            // We need user entity to calc days employed
            SysUser user = userMapper.selectUserById(userId);
            if (user != null) {
                refreshCurrentYearAccount(account, user);
            }
        }

        // Get current balances by expiry date (ordered by expiry date)
        // Broadened query: include non-expired balances regardless of start_date year
        List<LeaveRecord> availableBalances = recordMapper.selectAvailableBalances(userId);

        // Calculate available balance from each source
        Map<LocalDate, BigDecimal> balanceByExpiry = new HashMap<>();

        for (LeaveRecord record : availableBalances) {
            LocalDate expiry = record.getExpiryDate();
            balanceByExpiry.merge(expiry, record.getDays(), BigDecimal::add);
        }

        // Handle IMPLICIT Carry Over (Manually edited in Account but no Record)
        // If account.lastYearBalance > sum(CARRY_OVER records), use the difference
        BigDecimal recordedCarryOver = availableBalances.stream()
                .filter(r -> "CARRY_OVER".equals(r.getType()))
                .map(LeaveRecord::getDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal accountLastYearBalance = account.getLastYearBalance() != null ? account.getLastYearBalance()
                : BigDecimal.ZERO;

        if (accountLastYearBalance.compareTo(recordedCarryOver) > 0) {
            BigDecimal implicitCarryOver = accountLastYearBalance.subtract(recordedCarryOver);
            // Implicit carry over expires at end of current year
            LocalDate carryOverExpiry = LocalDate.of(year, 12, 31);
            balanceByExpiry.merge(carryOverExpiry, implicitCarryOver, BigDecimal::add);
            log.info("ℹ️ Detected implicit carry-over (from account): {} days", implicitCarryOver);
        }

        // Subtract already used amounts
        // Broadened query: include usage records (and EXPIRED records) for any bucket
        // Subtract already used amounts
        // Broadened query: include usage records (and EXPIRED records) for any bucket
        List<LeaveRecord> usageRecords = recordMapper.selectUsageRecords(userId);

        for (LeaveRecord usage : usageRecords) {
            LocalDate expiry = usage.getExpiryDate();
            if (balanceByExpiry.containsKey(expiry)) {
                balanceByExpiry.merge(expiry, usage.getDays(), BigDecimal::add);
            }
        }

        // NEW: Account for Floating Debt (expiry_date IS NULL)
        // These must be offset from the available buckets starting with the earliest
        // expiring ones.
        // NEW: Account for Floating Debt (expiry_date IS NULL)
        // These must be offset from the available buckets starting with the earliest
        // expiring ones.
        List<LeaveRecord> floatingRecords = recordMapper.selectFloatingRecords(userId);

        BigDecimal floatingDebt = floatingRecords.stream()
                .map(LeaveRecord::getDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (floatingDebt.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal debtToRecover = floatingDebt.abs();
            log.info("⚠️  Recovering floating debt of {} days from available buckets", debtToRecover);

            // Sort keys to recover from earliest buckets first
            List<LocalDate> expiryDates = new ArrayList<>(balanceByExpiry.keySet());
            java.util.Collections.sort(expiryDates);

            for (LocalDate expiry : expiryDates) {
                BigDecimal bucketBalance = balanceByExpiry.get(expiry);
                if (bucketBalance.compareTo(BigDecimal.ZERO) <= 0)
                    continue;

                BigDecimal offset = bucketBalance.min(debtToRecover);
                balanceByExpiry.put(expiry, bucketBalance.subtract(offset));
                debtToRecover = debtToRecover.subtract(offset);

                if (debtToRecover.compareTo(BigDecimal.ZERO) <= 0)
                    break;
            }
        }

        // Also include current year quota (no carry-over record, only account quota)
        LocalDate currentYearExpiry = LocalDate.of(year + 1, 12, 31);
        BigDecimal currentYearQuota = account.getActualQuota() != null ? account.getActualQuota() : BigDecimal.ZERO;

        // Calculate how much of current year quota has been used
        BigDecimal currentYearUsed = usageRecords.stream()
                .filter(r -> r.getExpiryDate() == null || r.getExpiryDate().equals(currentYearExpiry))
                .map(LeaveRecord::getDays)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal currentYearAvailable = currentYearQuota.subtract(currentYearUsed);
        balanceByExpiry.put(currentYearExpiry, currentYearAvailable);

        // Sort by expiry date (use earliest expiring first)
        List<Map.Entry<LocalDate, BigDecimal>> sortedBalances = new ArrayList<>(balanceByExpiry.entrySet());
        sortedBalances.sort(Map.Entry.comparingByKey());

        log.info("💰 Available balances by expiry: {}", balanceByExpiry);

        // Allocate requested days from earliest expiring balance first
        BigDecimal remainingToAllocate = daysToDeduct;

        for (Map.Entry<LocalDate, BigDecimal> entry : sortedBalances) {
            LocalDate expiryDate = entry.getKey();
            BigDecimal available = entry.getValue();

            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // Skip if no balance
            }

            if (remainingToAllocate.compareTo(BigDecimal.ZERO) <= 0) {
                break; // All allocated
            }

            // Determine how much to deduct from this source
            BigDecimal deduction = remainingToAllocate.min(available);

            // Create usage record for this source
            LeaveRecord usageRecord = new LeaveRecord();
            usageRecord.setUserId(userId);
            usageRecord.setStartDate(startDate);
            usageRecord.setEndDate(endDate);
            usageRecord.setDays(deduction.negate());
            usageRecord.setType(type); // Use the provided type (ANNUAL or ADJUSTMENT_DEDUCT)
            usageRecord.setExpiryDate(expiryDate);

            String source = expiryDate.getYear() == year ? "结转额度" : "当年额度";
            // Use custom remarks if provided, else format default
            String note = remarksPrefix;
            if (note == null || note.isEmpty()) {
                String typeStr = "ANNUAL".equals(type) ? "员工请假" : "额度扣除";
                note = String.format("%s (来自%s, 过期: %s)", typeStr, source, expiryDate);
            } else {
                note = String.format("%s (来自%s, 过期: %s)", note, source, expiryDate);
            }
            usageRecord.setRemarks(note);

            usageRecord.setCreateTime(LocalDateTime.now());

            recordMapper.insertRecord(usageRecord);

            log.info("  ✅ Allocated {} days from balance expiring on {}", deduction, expiryDate);

            remainingToAllocate = remainingToAllocate.subtract(deduction);
        }

        // Check if we need to borrow (Overdraft)
        if (remainingToAllocate.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("⚠️  Insufficient balance: {} days needed. Creating OVERDRAFT record.",
                    remainingToAllocate);

            // Create borrowing record
            LeaveRecord borrowRecord = new LeaveRecord();
            borrowRecord.setUserId(userId);
            borrowRecord.setStartDate(startDate);
            borrowRecord.setEndDate(endDate);
            borrowRecord.setDays(remainingToAllocate.negate());
            borrowRecord.setType(type);

            // DEBT MANAGEMENT MODEL:
            // Set expiry date to NULL to mark this as "General/Floating Debt"
            // This ensures it's NOT cleaned up by standard expiry tasks,
            // but can be offset during cleanup or cross-year initialization.
            borrowRecord.setExpiryDate(null);

            String note = remarksPrefix != null ? remarksPrefix : ("ANNUAL".equals(type) ? "员工请假" : "额度扣除");
            borrowRecord.setRemarks(String.format("%s (额度透支)", note));
            borrowRecord.setCreateTime(LocalDateTime.now());
            recordMapper.insertRecord(borrowRecord);

            log.info("  ✅ Created OVERDRAFT record for {} days (no expiry)", remainingToAllocate);
        }
    }

    @Override
    public LeaveAccountDTO getAccount(Long userId, Integer year) {
        return fillAccountDTO(new LeaveAccountDTO(), userId, year);
    }

    @Override
    public List<LeaveAccountDTO> getAllAccounts(Integer year) {
        List<SysUser> users = userMapper.selectActiveUsers();
        return users.stream()
                .map(user -> fillAccountDTO(new LeaveAccountDTO(), user.getId(), year))
                .collect(Collectors.toList());
    }

    @Override
    public Page<LeaveAccountDTO> getAllAccountsPage(Integer year, int current, int size) {
        // Filter out resigned users by default (implemented in XML)
        Page<SysUser> userPage = userMapper.selectActiveUsersPage(new Page<>(current, size));
        Page<LeaveAccountDTO> resultPage = new Page<>(current, size);
        resultPage.setTotal(userPage.getTotal());

        List<LeaveAccountDTO> dtoList = userPage.getRecords().stream()
                .map(user -> fillAccountDTO(new LeaveAccountDTO(), user.getId(), year))
                .collect(Collectors.toList());

        resultPage.setRecords(dtoList);
        return resultPage;
    }

    private LeaveAccountDTO fillAccountDTO(LeaveAccountDTO dto, Long userId, Integer year) {
        SysUser user = userMapper.selectUserById(userId);
        if (user == null) {
            return dto;
        }

        dto.setUserId(userId);
        dto.setUsername(user.getUsername());
        dto.setRealName(user.getRealName());
        dto.setEmployeeNumber(user.getEmployeeNumber());
        dto.setEntryDate(user.getEntryDate());
        dto.setYear(year);

        // Fetch last sync time from DingTalk sync job
        try {
            List<SysJob> jobs = jobMapper.selectAllJobs();
            Optional<SysJob> syncJob = jobs.stream()
                    .filter(j -> j.getInvokeTarget().contains("syncLeaveData"))
                    .findFirst();
            if (syncJob.isPresent() && syncJob.get().getLastRunTime() != null) {
                dto.setLastSyncTime(syncJob.get().getLastRunTime()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            } else {
                dto.setLastSyncTime("暂无同步记录");
            }
        } catch (Exception e) {
            log.error("Failed to fetch last sync time", e);
            dto.setLastSyncTime("获取失败");
        }

        // Only query existing account, DO NOT auto-initialize
        // Initialization should only happen through scheduled tasks or manual execution
        LeaveAccount account = accountMapper.selectAccountByUserIdAndYear(userId, year);

        // Dynamically refresh if it is current year
        if (account != null && year == LocalDate.now().getYear()) {
            refreshCurrentYearAccount(account, user);
        }

        if (account != null) {
            dto.setId(account.getId());
            dto.setSocialSeniority(account.getSocialSeniority());
            dto.setStandardQuota(account.getStandardQuota());
            dto.setActualQuota(account.getActualQuota());
            dto.setLastYearBalance(account.getLastYearBalance());
            dto.setDaysEmployed(account.getDaysEmployed());

            // Get records for this year directly from DB to avoid fetching all history
            List<LeaveRecord> yearRecords = recordMapper.selectRecordsByYear(userId, year);

            dto.setRecords(yearRecords);

            // Calculate 'Used' (ANNUAL/LEAVE types) - these are stored as negative numbers
            BigDecimal calculatedUsed = yearRecords.stream()
                    .filter(r -> "ANNUAL".equals(r.getType()))
                    .map(LeaveRecord::getDays)
                    .filter(days -> days.compareTo(BigDecimal.ZERO) < 0) // Only count negative records as usage
                    .map(BigDecimal::abs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            dto.setCurrentYearUsed(calculatedUsed);

            // Calculate Total Balance = LastYearBalance + ActualQuota + Sum(All Records for
            // this year excluding CARRY_OVER)
            // Note: Usage records are negative, so adding them reduces the balance.
            // CARRY_OVER is excluded because it's already in LastYearBalance (if we
            // consider cleanup logic)
            // OR if it's a fresh record for this year.
            // The `CARRY_OVER` record is just for history.
            // So we EXCLUDE CARRY_OVER record from the sum to avoid double counting it.

            // Calculate Total Balance for this year's view (Year-local records):
            // Total = lastYearBalance (stored in account)
            // + actualQuota (stored in account or refreshed)
            // + sum(all records of this year except CARRY_OVER)
            // Note: CARRY_OVER is excluded because it's already reflected in
            // lastYearBalance.
            BigDecimal recordsSum = yearRecords.stream()
                    .filter(r -> !"CARRY_OVER".equals(r.getType()))
                    .map(LeaveRecord::getDays)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal lastYearBalance = account.getLastYearBalance() != null ? account.getLastYearBalance()
                    : BigDecimal.ZERO;
            BigDecimal actualQuota = account.getActualQuota() != null ? account.getActualQuota() : BigDecimal.ZERO;

            dto.setTotalBalance(lastYearBalance.add(actualQuota).add(recordsSum));

        } else {
            // Account does not exist - return empty DTO instead of auto-creating
            dto.setSocialSeniority(0);
            dto.setStandardQuota(BigDecimal.ZERO);
            dto.setActualQuota(BigDecimal.ZERO);
            dto.setLastYearBalance(BigDecimal.ZERO);
            dto.setCurrentYearUsed(BigDecimal.ZERO);
            dto.setDaysEmployed(0);
            dto.setTotalBalance(BigDecimal.ZERO);
            dto.setRecords(java.util.Collections.emptyList());
        }

        return dto;
    }

    @Override
    public List<LeaveRecord> getHistory(Long userId, Integer year) {
        return recordMapper.selectHistory(userId, year);
    }

    @Override
    public List<LeaveRecord> getAllRecords() {
        return recordMapper.findAllRecords();
    }

    @Override
    @Transactional
    public void updateRecord(LeaveRecord record) {
        recordMapper.updateRecord(record);
    }

    @Override
    @Transactional
    public void addRecord(LeaveRecord record) {
        if (record.getCreateTime() == null) {
            record.setCreateTime(LocalDateTime.now());
        }

        // SPECIAL HANDLING FOR "ANNUAL" TYPE (Deductions)
        // If adding an ANNUAL record with days (usually negative or user intends
        // deduction),
        // we should route it through the priority deduction logic IF days are negative.
        // The admin might input positive days in UI, but controller/service usually
        // flips it to negative.
        // Let's check:
        // In frontend, "ANNUAL" usually means usage.

        // Check if this is an ANNUAL usage record that needs priority deduction logic
        // Condition: Type is ANNUAL and (days is negative OR it's meant to be usage)
        // Admin UI might send positive days for "Backfill", but previous logic negated
        // it.

        // Logic:
        // 1. Determine days magnitude
        BigDecimal days = record.getDays();
        if (days == null)
            return; // Should not happen

        BigDecimal absDays = days.abs();

        // 2. Decide if we use standard insert or priority deduction (any deduction
        // type)
        if ("ANNUAL".equals(record.getType()) || "ADJUSTMENT_DEDUCT".equals(record.getType())) {
            // Use priority deduction for ANY deduction type to ensure expiry_date is set
            log.info("🔄 Routing manual {} record to priority deduction logic. Days: {}", record.getType(), absDays);
            deductLeaveDays(record.getUserId(), absDays, record.getStartDate(), record.getEndDate(),
                    record.getType(), record.getRemarks());
            return;
        }

        // AUTO-SET EXPIRY DATE (if not already set)
        if (record.getExpiryDate() == null && record.getStartDate() != null) {
            String type = record.getType();
            if ("ADJUSTMENT_ADD".equals(type)) { // Only for ADD, since ANNUAL is handled above
                // Current year quota expires at end of next year (2-year validity)
                int recordYear = record.getStartDate().getYear();
                record.setExpiryDate(LocalDate.of(recordYear + 1, 12, 31));
                log.debug("Auto-set expiry date for {} record: {}", type, record.getExpiryDate());
            }
        }

        // AUTO-SIGN LOGIC:
        // Deductions (Negative): ADJUSTMENT_DEDUCT, EXPIRED (ANNUAL handled above)
        // Additions (Positive): ADJUSTMENT_ADD, CARRY_OVER
        String type = record.getType();
        if ("ADJUSTMENT_DEDUCT".equals(type) || "EXPIRED".equals(type)) {
            record.setDays(absDays.negate());
        } else {
            record.setDays(absDays);
        }

        recordMapper.insertRecord(record);
        log.info("Added record: userId={}, type={}, days={}, expiryDate={}",
                record.getUserId(), record.getType(), record.getDays(), record.getExpiryDate());
    }

    @Override
    @Transactional
    public void updateAccount(LeaveAccount account) {
        accountMapper.updateAccount(account);
    }

    @Override
    @Transactional
    public void deleteAccountsByUserId(Long userId) {
        accountMapper.deleteByUserId(userId);
        log.info("Soft deleted all leave accounts for user {}", userId);
    }

    @Override
    public List<Integer> getAllAvailableYears() {
        return accountMapper.selectDistinctYears();
    }

}