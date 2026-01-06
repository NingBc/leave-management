package com.leave.system.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leave.system.dto.LeaveAccountDTO;
import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.exception.BusinessException;
import com.leave.system.mapper.LeaveAccountMapper;
import com.leave.system.mapper.LeaveRecordMapper;
import com.leave.system.mapper.SysUserMapper;
import com.leave.system.service.LeaveService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public LeaveServiceImpl(LeaveAccountMapper accountMapper, LeaveRecordMapper recordMapper,
            SysUserMapper userMapper) {
        this.accountMapper = accountMapper;
        this.recordMapper = recordMapper;
        this.userMapper = userMapper;
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

        // Get last year's account to get the actualQuota
        // Get last year's account to get the actualQuota
        LeaveAccount lastYearAccount = accountMapper.selectLastYearAccount(userId, year);

        if (lastYearAccount == null) {
            log.info("❌ No account found for year {}, cannot carry over", lastYear);
            return BigDecimal.ZERO;
        }

        // Get the base quota for last year
        BigDecimal lastYearQuota = lastYearAccount.getActualQuota() != null
                ? lastYearAccount.getActualQuota()
                : BigDecimal.ZERO;

        log.info("📊 Last year quota: {}", lastYearQuota);

        // Get all last year's records (usage, adjustments, carry-over from previous
        // year)
        // EXCLUDE records with NULL expiry (Floating Debt Pool) to avoid
        // double-deduction during cleanup
        // Get all last year's records (usage, adjustments, carry-over from previous
        // year)
        // EXCLUDE records with NULL expiry (Floating Debt Pool) to avoid
        // double-deduction during cleanup
        List<LeaveRecord> lastYearRecords = recordMapper.selectRecordsForCarryOver(userId, lastYear);

        log.info("📋 Found {} bucketed records (with expiry) in year {}", lastYearRecords.size(), lastYear);

        LocalDate carryOverCheckDate = LocalDate.of(year, 1, 1); // Jan 1 of new year

        // Calculate net change from records (excluding expired ones)
        BigDecimal netChange = BigDecimal.ZERO;

        for (LeaveRecord record : lastYearRecords) {
            BigDecimal days = record.getDays();
            LocalDate expiry = record.getExpiryDate();

            log.debug("  Record: type={}, days={}, expiry={}", record.getType(), days, expiry);

            // Skip if already expired
            if (expiry != null && expiry.isBefore(carryOverCheckDate)) {
                log.info("  ⏭️  Skipping expired record: {} days, type={}, expired on {}",
                        days, record.getType(), expiry);
                continue;
            }

            // Skip EXPIRED type records as they are system balancing entries
            if ("EXPIRED".equals(record.getType())) {
                log.info("  ⏭️  Skipping EXPIRED system record: {} days", days);
                continue;
            }

            netChange = netChange.add(days);
            if (days.compareTo(BigDecimal.ZERO) > 0) {
                log.info("  ➕ Addition: {} days (type={})", days, record.getType());
            } else {
                log.info("  ➖ Usage/Deduction: {} days (type={})", days, record.getType());
            }
        }

        // Total balance = quota + net change from records
        BigDecimal remaining = lastYearQuota.add(netChange);
        log.info("💰 Calculation: {} (quota) + {} (net records) = {} remaining",
                lastYearQuota, netChange, remaining);

        // Allow negative carry over (Debt)
        // if (remaining.compareTo(BigDecimal.ZERO) <= 0) { ... } -> Removed capping
        // check

        // Round down to nearest 0.5
        BigDecimal carryOver = remaining
                .divide(new BigDecimal("0.5"), 0, RoundingMode.DOWN)
                .multiply(new BigDecimal("0.5"));

        log.info("✅ Final carry-over for user {} year {}: {} days", userId, year, carryOver);

        return carryOver;
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

        // If year is in the future, return 0
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
        Long userId = user.getId();

        // Calculate seniority as of today
        int seniority = 0;
        if (user.getFirstWorkDate() != null) {
            java.time.Period period = java.time.Period.between(user.getFirstWorkDate(), LocalDate.now());
            seniority = period.getYears();
        }

        // Company Policy: <10 yr: 5, 10-20 yr: 10, >=20 yr: 15
        BigDecimal standardQuota;
        if (seniority < 10) {
            standardQuota = new BigDecimal("5.0");
        } else if (seniority < 20) {
            standardQuota = new BigDecimal("10.0");
        } else {
            standardQuota = new BigDecimal("15.0");
        }

        // Calculate Days Employed in this year
        int daysEmployed = calculateDaysEmployed(user.getEntryDate(), year);

        // Calculate Actual Quota based on days employed
        BigDecimal daysInYear = new BigDecimal(LocalDate.of(year, 12, 31).getDayOfYear());
        BigDecimal rawQuota = standardQuota
                .multiply(new BigDecimal(daysEmployed))
                .divide(daysInYear, 10, RoundingMode.HALF_UP);

        // Round down to nearest 0.5
        BigDecimal actualQuota = rawQuota.multiply(new BigDecimal("2"))
                .setScale(0, RoundingMode.FLOOR)
                .divide(new BigDecimal("2"), 1, RoundingMode.FLOOR);

        // Calculate carry over from last year (excluding expired balances)
        BigDecimal carryOver = BigDecimal.ZERO;
        LeaveAccount lastYearAccount = accountMapper.selectLastYearAccount(userId, year);

        if (lastYearAccount != null) {
            // Use new calculation method that considers expiry dates
            carryOver = calculateCarryOverBalance(userId, year);

            // Create or update CARRY_OVER record
            // Create or update CARRY_OVER record
            LeaveRecord existingCarryOver = recordMapper.selectCarryOverRecord(userId, LocalDate.of(year, 1, 1));

            // Carry-over expiry date: end of current year (2-year validity)
            LocalDate carryOverExpiryDate = LocalDate.of(year, 12, 31);

            if (existingCarryOver != null) {
                // Update existing record
                existingCarryOver.setDays(carryOver);
                existingCarryOver.setExpiryDate(carryOverExpiryDate);
                existingCarryOver.setRemarks("上年结余年假结转 (过期: " + carryOverExpiryDate + ")");
                recordMapper.updateRecord(existingCarryOver);
                log.info("✅ Updated carry-over record: {} days (expires {})", carryOver, carryOverExpiryDate);
            } else {
                // Create new record
                LeaveRecord carryOverRecord = new LeaveRecord();
                carryOverRecord.setUserId(userId);
                carryOverRecord.setStartDate(LocalDate.of(year, 1, 1));
                carryOverRecord.setEndDate(LocalDate.of(year, 1, 1));
                carryOverRecord.setDays(carryOver);
                carryOverRecord.setType("CARRY_OVER");
                carryOverRecord.setExpiryDate(carryOverExpiryDate);
                carryOverRecord.setRemarks("上年结余年假结转 (过期: " + carryOverExpiryDate + ")");
                carryOverRecord.setCreateTime(LocalDateTime.now());
                recordMapper.insertRecord(carryOverRecord);
                log.info("✅ Created carry-over record: {} days (expires {})", carryOver, carryOverExpiryDate);
            }
        }

        // Check if account exists (INCLUDING deleted ones)
        LeaveAccount account = accountMapper.selectAccountByUserIdAndYearIncludeDeleted(userId, year);

        if (account == null) {
            // Create new account
            account = new LeaveAccount();
            account.setUserId(userId);
            account.setYear(year);
            account.setSocialSeniority(seniority);
            account.setStandardQuota(standardQuota);
            account.setActualQuota(actualQuota);
            account.setLastYearBalance(carryOver);
            account.setCurrentYearUsed(BigDecimal.ZERO);
            account.setDaysEmployed(daysEmployed);
            account.setDeleted(0); // Ensure active
            accountMapper.insertAccount(account);
            log.info("Created new account for user {} year {}", userId, year);
        } else {
            // Update existing account (and restore if deleted)
            if (account.getDeleted() != null && account.getDeleted() == 1) {
                log.info("Restoring logically deleted account for user {} year {}", userId, year);
                account.setDeleted(0);
            }
            account.setSocialSeniority(seniority);
            account.setStandardQuota(standardQuota);
            account.setActualQuota(actualQuota);
            account.setDaysEmployed(daysEmployed);

            // If we are restoring, we might need to be careful about carryOver overwriting?
            // Current logic does not update lastYearBalance on update path, only on
            // creation.
            // But if it was deleted, maybe we should re-eval carryOver?
            // For now, let's keep stick to original logic: only set carryOver on creation.
            // Wait, if it was deleted, it's effectively "re-created".
            // If I restore it, should I reset carryOver?
            // Let's assume restoration implies "it's back", and we update Quotas.
            // Use existing lastYearBalance?
            // If the user was deleted/resigned then re-hired?
            // If re-hired, maybe we should treat as new? But unique key constraints says
            // NO.
            // So we MUST reuse this row.

            accountMapper.updateAccount(account);
            log.info("Updated account for user {} year {}", userId, year);
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
        int year = startDate.getYear();

        // Ensure account exists
        LeaveAccount account = accountMapper.selectAccountByUserIdAndYear(userId, year);
        if (account == null) {
            initYearlyAccount(userId, year);
            account = accountMapper.selectAccountByUserIdAndYear(userId, year);
        }

        // Calculate days requested
        long daysDiff = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        BigDecimal daysRequested = new BigDecimal(daysDiff);

        if (daysRequested.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Invalid date range");
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

            // Calculate Total Balance using "Floating Pool Sovereignty" model:
            // Total = lastYearBalance (Bucket Carry-over)
            // + actualQuota (Current Year Bucket)
            // + currentYearBucketChange (Bucketed records for this year)
            // + globalFloatingDebt (All NULL-expiry records)

            // 1. Current Year Bucketed Change (records with expiry, excluding carry-over)
            BigDecimal currentYearBucketChange = yearRecords.stream()
                    .filter(r -> !"CARRY_OVER".equals(r.getType()) && r.getExpiryDate() != null)
                    .map(LeaveRecord::getDays)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 2. Global Floating Debt (Sum of all NULL-expiry records regardless of year)
            // These records represent floating debt/adjustments not tied to a specific
            // bucket.
            BigDecimal globalFloatingDebt = recordMapper.selectFloatingRecords(userId)
                    .stream()
                    .map(LeaveRecord::getDays)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal lastYearBalance = account.getLastYearBalance() != null ? account.getLastYearBalance()
                    : BigDecimal.ZERO;
            BigDecimal actualQuota = account.getActualQuota() != null ? account.getActualQuota() : BigDecimal.ZERO;

            dto.setTotalBalance(lastYearBalance.add(actualQuota)
                    .add(currentYearBucketChange)
                    .add(globalFloatingDebt));

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