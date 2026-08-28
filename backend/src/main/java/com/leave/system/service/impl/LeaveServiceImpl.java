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

    /** 管理页允许手工新增的记录类型 */
    private static final Set<String> ALLOWED_MANUAL_TYPES = Set.of("ANNUAL", "ADJUSTMENT_ADD", "ADJUSTMENT_DEDUCT");

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
     * 计算跨年结转余额 (已扣除过期作废的部分)。
     *
     * <p>
     * 实现建立在与扣减、显示完全相同的 {@link #buildBuckets} 桶账本之上:
     * <ol>
     * <li>先把上年度账户按<b>整年</b>补算 —— 上年 12 月 31 日之后不会再有人访问该年度账户,
     * actual_quota 常常冻结在年中的某个残值上。旧实现直接读这个残值做结转, 员工会凭空少拿天数。</li>
     * <li>构建上年度桶账本, 用未过期额度优先冲抵浮动债务 (透支)。</li>
     * <li>丢弃在目标年 1 月 1 日已经过期的桶, 剩余各桶之和即为结转值 (可以为负, 表示欠账结转)。</li>
     * </ol>
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

        // 1. 上年度额度补算到整年 (关键: 否则结转基数是年中冻结的残值)
        SysUser user = userMapper.selectUserById(userId);
        if (user != null) {
            recalcQuotaFields(lastYearAccount, user, lastYear);
            accountMapper.updateAccount(lastYearAccount);
        } else {
            log.warn("⚠️ User {} not found, carrying over with stored quota {}", userId, lastYearAccount.getActualQuota());
        }

        // 2. 上年度桶账本 + 债务冲抵
        TreeMap<LocalDate, BigDecimal> buckets = buildBuckets(lastYearAccount, lastYear, LocalDate.of(lastYear, 1, 1));
        log.info("📊 Year {} buckets before expiry: {}", lastYear, buckets);
        settleFloatingDebt(buckets);

        // 3. 丢弃已过期的桶; 浮动债务 (null 桶) 不会过期, 随结转带入下一年
        LocalDate jan1 = LocalDate.of(year, 1, 1);
        BigDecimal carryOver = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, BigDecimal> entry : buckets.entrySet()) {
            LocalDate expiry = entry.getKey();
            if (expiry != null && expiry.isBefore(jan1)) {
                log.info("  ⏭️  Expired bucket {}: {} days dropped", expiry, entry.getValue());
                continue;
            }
            log.info("  ➕ Carried over bucket {}: {} days", expiry == null ? "FLOATING" : expiry, entry.getValue());
            carryOver = carryOver.add(entry.getValue());
        }

        log.info("💰 Final carry-over for user {} year {}: {} days", userId, year, carryOver);
        return carryOver;
    }

    /**
     * 计算工龄与在职天数的参照日期: 目标年度的 12 月 31 日, 但不晚于今天。
     *
     * <p>
     * 旧实现一律用 {@code LocalDate.now()}, 于是重算历史年度时会套用「今天」的工龄档,
     * 例如 2026 年重跑 2024 年初始化, 会按 2026 年的工龄发 2024 年的额度。
     */
    private LocalDate quotaReferenceDate(int year) {
        LocalDate endOfYear = LocalDate.of(year, 12, 31);
        LocalDate today = LocalDate.now();
        return today.isBefore(endOfYear) ? today : endOfYear;
    }

    /**
     * 累计工作年限 (社会工龄), 以 {@link #quotaReferenceDate} 为准。
     */
    private int calculateSeniority(LocalDate firstWorkDate, int year) {
        if (firstWorkDate == null) {
            return 0;
        }
        LocalDate reference = quotaReferenceDate(year);
        if (firstWorkDate.isAfter(reference)) {
            return 0;
        }
        return java.time.Period.between(firstWorkDate, reference).getYears();
    }

    /**
     * Calculate days employed in a specific year.
     *
     * <p>
     * 区间为 [max(入职日, 1/1), min(离职日, 12/31, 今天)]。
     * <ul>
     * <li>离职日纳入计算: 离职之后不再累计额度 (旧实现完全忽略 resignation_date)。</li>
     * <li>入职日为空时不再直接按整年算: 当年度同样截到今天, 否则新员工 1 月 1 日就拿满额度,
     * 与有入职日的分支自相矛盾。</li>
     * </ul>
     */
    private int calculateDaysEmployed(LocalDate entryDate, LocalDate resignationDate, int year) {
        LocalDate today = LocalDate.now();
        if (year > today.getYear()) {
            return 0;
        }

        LocalDate startOfYear = LocalDate.of(year, 1, 1);
        LocalDate endOfPeriod = quotaReferenceDate(year);

        if (resignationDate != null && resignationDate.isBefore(endOfPeriod)) {
            endOfPeriod = resignationDate;
        }
        if (endOfPeriod.isBefore(startOfYear)) {
            return 0;
        }

        LocalDate effectiveStartDate = (entryDate == null || entryDate.isBefore(startOfYear))
                ? startOfYear
                : entryDate;
        if (effectiveStartDate.isAfter(endOfPeriod)) {
            return 0;
        }

        return (int) ChronoUnit.DAYS.between(effectiveStartDate, endOfPeriod) + 1;
    }

    /**
     * 重算并写入账户的额度字段 (工龄 / 标准额度 / 在职天数 / 实际额度)。
     * 这是全系统唯一的额度公式, initYearlyAccount 与当年度动态刷新都走这里,
     * 避免两处公式漂移 —— 旧实现的动态刷新不重算工龄档, 员工年中满 10 年也拿不到增量。
     *
     * <p>
     * 不触碰 last_year_balance, 也不落库, 由调用方决定何时写。
     *
     * @return 是否有字段发生变化
     */
    private boolean recalcQuotaFields(LeaveAccount account, SysUser user, int year) {
        int seniority = calculateSeniority(user.getFirstWorkDate(), year);
        BigDecimal standardQuota = getQuotaBySeniority(seniority);
        int daysEmployed = calculateDaysEmployed(user.getEntryDate(), user.getResignationDate(), year);

        // 实际额度 = 标准额度 × 在职天数 / 全年天数, 向下取整到 0.5
        BigDecimal daysInYear = new BigDecimal(LocalDate.of(year, 12, 31).getDayOfYear());
        BigDecimal actualQuota = standardQuota
                .multiply(new BigDecimal(daysEmployed))
                .divide(daysInYear, 10, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("2"))
                .setScale(0, RoundingMode.FLOOR)
                .divide(new BigDecimal("2"), 1, RoundingMode.FLOOR);

        boolean changed = !Objects.equals(account.getSocialSeniority(), seniority)
                || !Objects.equals(account.getDaysEmployed(), daysEmployed)
                || account.getStandardQuota() == null
                || account.getStandardQuota().compareTo(standardQuota) != 0
                || account.getActualQuota() == null
                || account.getActualQuota().compareTo(actualQuota) != 0;

        account.setSocialSeniority(seniority);
        account.setStandardQuota(standardQuota);
        account.setDaysEmployed(daysEmployed);
        account.setActualQuota(actualQuota);
        return changed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LeaveAccount initYearlyAccount(Long userId, Integer year) {
        SysUser user = userMapper.selectUserById(userId);
        if (user == null) {
            throw new BusinessException("User not found");
        }
        // 初始化 = 重算额度 + 重算上年结转
        return upsertAccount(user, year, true);
    }

    /**
     * 只刷新额度字段, <b>不重算上年结余</b>。
     *
     * <p>
     * 供「用户档案变更」这类顺带刷新的场景使用。上年结余允许管理员在页面上手工修正,
     * 若这里一并重算, 任何一次用户资料编辑都会把修正值冲掉 —— 而页面上明写着「可手动修正」。
     * 需要重算结转请走 {@link #initYearlyAccount}。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public LeaveAccount refreshAccount(SysUser user, Integer year) {
        return upsertAccount(user, year, false);
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
     * 新建或更新年度账户。
     *
     * @param recalculateCarryOver 是否重算上年结转 (并同步 CARRY_OVER 留痕记录)。
     *                             false 时保留账户上已有的 last_year_balance,
     *                             以免覆盖管理员的手工修正。
     */
    private LeaveAccount upsertAccount(SysUser user, Integer year, boolean recalculateCarryOver) {
        Long userId = user.getId();

        LeaveAccount scratch = new LeaveAccount();
        recalcQuotaFields(scratch, user, year);

        BigDecimal carryOverBalance = null;
        if (recalculateCarryOver && accountMapper.selectLastYearAccount(userId, year) != null) {
            carryOverBalance = calculateCarryOverBalance(userId, year);
            upsertCarryOverRecord(userId, year, carryOverBalance);
        }

        LeaveAccount account = accountMapper.selectAccountByUserIdAndYearIncludeDeleted(userId, year);
        boolean isNew = account == null;
        if (isNew) {
            account = new LeaveAccount();
            account.setUserId(userId);
            account.setYear(year);
            account.setLastYearBalance(BigDecimal.ZERO);
        }
        if (account.getDeleted() != null && account.getDeleted() == 1) {
            account.setDeleted(0);
        }

        account.setSocialSeniority(scratch.getSocialSeniority());
        account.setStandardQuota(scratch.getStandardQuota());
        account.setActualQuota(scratch.getActualQuota());
        account.setDaysEmployed(scratch.getDaysEmployed());
        if (carryOverBalance != null) {
            account.setLastYearBalance(carryOverBalance);
        }

        if (isNew) {
            account.setDeleted(0);
            accountMapper.insertAccount(account);
        } else {
            accountMapper.updateAccount(account);
        }
        return account;
    }

    /** 写入/更新 CARRY_OVER 留痕记录 (仅供页面展示结转来源, 余额以 last_year_balance 为准) */
    private void upsertCarryOverRecord(Long userId, int year, BigDecimal carryOverBalance) {
        LocalDate startOfYear = LocalDate.of(year, 1, 1);
        LocalDate expiryDate = LocalDate.of(year, 12, 31);
        String remarks = String.format("上年结余年假结转 (过期: %s)", expiryDate);

        LeaveRecord carryRecord = recordMapper.selectCarryOverRecord(userId, startOfYear);
        if (carryRecord != null) {
            carryRecord.setDays(carryOverBalance);
            carryRecord.setRemarks(remarks);
            carryRecord.setExpiryDate(expiryDate);
            recordMapper.updateRecord(carryRecord);
            return;
        }

        carryRecord = new LeaveRecord();
        carryRecord.setUserId(userId);
        carryRecord.setStartDate(startOfYear);
        carryRecord.setEndDate(startOfYear);
        carryRecord.setDays(carryOverBalance);
        carryRecord.setType("CARRY_OVER");
        carryRecord.setRemarks(remarks);
        carryRecord.setExpiryDate(expiryDate);
        carryRecord.setCreateTime(LocalDateTime.now());
        recordMapper.insertRecord(carryRecord);
    }

    /**
     * 当年度账户按天动态刷新。
     *
     * <p>
     * 当年额度是按在职天数逐日累计的, 所以每次读取/扣减前都要重算。复用
     * {@link #recalcQuotaFields} 这个唯一公式, 工龄档也会一并重算 —— 旧实现固定沿用
     * 年初的 standard_quota, 员工年中累计工龄满 10 / 20 年拿不到应有的增量。
     */
    private void refreshCurrentYearAccount(LeaveAccount account, SysUser user) {
        int year = account.getYear();
        LocalDate today = LocalDate.now();

        // Only refresh for current year
        if (year != today.getYear()) {
            return;
        }

        if (recalcQuotaFields(account, user, year)) {
            accountMapper.updateAccount(account);
            log.info("✅ Refreshed dynamic quota for user {}: employed={} days, quota={}",
                    user.getId(), account.getDaysEmployed(), account.getActualQuota());
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
     * 构建某用户在指定年度的年假「桶账本」—— 全系统唯一的余额计算入口。
     *
     * <p>
     * 桶的 key 是到期日, value 是该桶净余额; key 为 {@code null} 的桶代表<b>浮动债务</b>
     * (透支, 不挂在任何到期批次上)。构成:
     * <ul>
     * <li>{@code [year+1-12-31]} += 当年额度 actual_quota (两年有效期)</li>
     * <li>{@code [year-12-31]} += 上年结转 last_year_balance (今年底作废)</li>
     * <li>目标年度 1 月 1 日之后的所有非 CARRY_OVER 流水, 按各自 expiry_date 并入对应桶</li>
     * </ul>
     *
     * <p>
     * <b>为什么不取更早年度的流水:</b> 上年及以前的 ADJUSTMENT_ADD / ANNUAL / 透支
     * 已经由 {@code calculateCarryOverBalance} 折算进 last_year_balance, 再取一遍就是重复计算。
     * 旧实现用 {@code selectAvailableBalances}(按 expiry_date 过滤) 取额度、
     * 用 {@code selectFloatingRecords}(全历史) 取债务, 两边口径不一致, 导致
     * 跨年的 ADJUSTMENT_ADD 被算两次、跨年的透支债被扣两次。
     *
     * @param account  目标年度账户 (调用方负责保证 actual_quota 已刷新)
     * @param year     目标年度
     * @param asOfDate 时点; 在此之前就已过期的桶不计入
     * @return 有序桶账本, null 桶排在最后
     */
    private TreeMap<LocalDate, BigDecimal> buildBuckets(LeaveAccount account, int year, LocalDate asOfDate) {
        // null 排最后: 浮动债务没有到期日, 排序时永远靠后
        TreeMap<LocalDate, BigDecimal> buckets = new TreeMap<>(
                Comparator.nullsLast(Comparator.naturalOrder()));

        LocalDate quotaExpiry = LocalDate.of(year + 1, 12, 31);
        LocalDate carryOverExpiry = LocalDate.of(year, 12, 31);

        BigDecimal actualQuota = account.getActualQuota() != null ? account.getActualQuota() : BigDecimal.ZERO;
        BigDecimal lastYearBalance = account.getLastYearBalance() != null ? account.getLastYearBalance()
                : BigDecimal.ZERO;

        buckets.merge(quotaExpiry, actualQuota, BigDecimal::add);
        buckets.merge(carryOverExpiry, lastYearBalance, BigDecimal::add);

        for (LeaveRecord record : recordMapper.selectLedgerRecords(account.getUserId(), LocalDate.of(year, 1, 1))) {
            BigDecimal days = record.getDays() != null ? record.getDays() : BigDecimal.ZERO;
            LocalDate expiry = record.getExpiryDate();

            // 到期日晚于当年额度桶的流水属于以后年度, 不参与本年度账本
            if (expiry != null && expiry.isAfter(quotaExpiry)) {
                continue;
            }
            buckets.merge(expiry, days, BigDecimal::add);
        }

        // 丢弃在 asOfDate 之前就已过期的桶 (正数已作废, 负数已由结转/过期清理处理过)
        buckets.keySet().removeIf(expiry -> expiry != null && expiry.isBefore(asOfDate));

        return buckets;
    }

    /** 桶账本合计, 即该年度的年假总余额 (浮动债务为负数, 自然被减掉) */
    private BigDecimal sumBuckets(Map<LocalDate, BigDecimal> buckets) {
        return buckets.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 用正数桶冲抵浮动债务 (透支), 按到期日从早到晚。
     * 冲抵后 null 桶只保留仍未偿还的部分。
     */
    private void settleFloatingDebt(TreeMap<LocalDate, BigDecimal> buckets) {
        BigDecimal floating = buckets.get(null);
        if (floating == null || floating.compareTo(BigDecimal.ZERO) >= 0) {
            return;
        }

        BigDecimal debt = floating.negate();
        log.info("⚠️  Settling floating debt of {} days from available buckets", debt);

        for (Map.Entry<LocalDate, BigDecimal> entry : buckets.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            BigDecimal credit = entry.getValue();
            if (credit.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal offset = credit.min(debt);
            entry.setValue(credit.subtract(offset));
            debt = debt.subtract(offset);
            if (debt.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
        }

        buckets.put(null, debt.negate());
    }

    /**
     * Core logic to deduct leave days from available balances with priority
     */
    private void deductLeaveDays(Long userId, BigDecimal daysToDeduct, LocalDate startDate, LocalDate endDate,
            String type, String remarksPrefix) {
        if (startDate == null) {
            throw new BusinessException("休假开始日期不能为空");
        }
        if (endDate == null) {
            endDate = startDate;
        }
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("结束日期不能早于开始日期");
        }
        if (daysToDeduct == null || daysToDeduct.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("扣减天数必须大于 0: " + daysToDeduct);
        }

        int year = startDate.getYear();

        // Ensure account exists or init it (needed for quota info)
        LeaveAccount account = accountMapper.selectAccountByUserIdAndYear(userId, year);
        if (account == null) {
            initYearlyAccount(userId, year);
            account = accountMapper.selectAccountByUserIdAndYear(userId, year);
        }
        if (account == null) {
            throw new BusinessException("无法为用户 " + userId + " 创建 " + year + " 年度年假账户");
        }

        // Ensure quota is fresh for current year before deduction checks
        if (year == LocalDate.now().getYear()) {
            SysUser user = userMapper.selectUserById(userId);
            if (user != null) {
                refreshCurrentYearAccount(account, user);
            }
        }

        TreeMap<LocalDate, BigDecimal> buckets = buildBuckets(account, year, startDate);
        // 先还债再放款: 历史透支必须优先从可用额度里扣回, 否则同一笔债会被反复忽略
        settleFloatingDebt(buckets);

        log.info("💰 Available balances by expiry: {}", buckets);

        BigDecimal remainingToAllocate = daysToDeduct;

        for (Map.Entry<LocalDate, BigDecimal> entry : buckets.entrySet()) {
            LocalDate expiryDate = entry.getKey();
            BigDecimal available = entry.getValue();

            if (expiryDate == null || available.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // null 桶是债务; 非正数桶没有可用额度
            }
            if (remainingToAllocate.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal deduction = remainingToAllocate.min(available);

            LeaveRecord usageRecord = new LeaveRecord();
            usageRecord.setUserId(userId);
            usageRecord.setStartDate(startDate);
            usageRecord.setEndDate(endDate);
            usageRecord.setDays(deduction.negate());
            usageRecord.setType(type);
            usageRecord.setExpiryDate(expiryDate);
            usageRecord.setRemarks(buildRemarks(remarksPrefix, type, expiryDate, year));
            usageRecord.setCreateTime(LocalDateTime.now());
            recordMapper.insertRecord(usageRecord);

            log.info("  ✅ Allocated {} days from balance expiring on {}", deduction, expiryDate);

            remainingToAllocate = remainingToAllocate.subtract(deduction);
        }

        // Check if we need to borrow (Overdraft)
        if (remainingToAllocate.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("⚠️  Insufficient balance: {} days needed. Creating OVERDRAFT record.", remainingToAllocate);

            LeaveRecord borrowRecord = new LeaveRecord();
            borrowRecord.setUserId(userId);
            borrowRecord.setStartDate(startDate);
            borrowRecord.setEndDate(endDate);
            borrowRecord.setDays(remainingToAllocate.negate());
            borrowRecord.setType(type);

            // DEBT MANAGEMENT MODEL:
            // expiry_date = NULL 表示「浮动债务」: 不属于任何到期批次, 不会被过期清理作废,
            // 但会在后续扣减 (settleFloatingDebt) 与跨年结转时优先冲抵。
            borrowRecord.setExpiryDate(null);

            String note = remarksPrefix != null ? remarksPrefix : ("ANNUAL".equals(type) ? "员工请假" : "额度扣除");
            borrowRecord.setRemarks(String.format("%s (额度透支)", note));
            borrowRecord.setCreateTime(LocalDateTime.now());
            recordMapper.insertRecord(borrowRecord);

            log.info("  ✅ Created OVERDRAFT record for {} days (no expiry)", remainingToAllocate);
        }
    }

    private String buildRemarks(String remarksPrefix, String type, LocalDate expiryDate, int year) {
        String source = expiryDate.getYear() == year ? "结转额度" : "当年额度";
        String note = remarksPrefix;
        if (note == null || note.isEmpty()) {
            note = "ANNUAL".equals(type) ? "员工请假" : "额度扣除";
        }
        return String.format("%s (来自%s, 过期: %s)", note, source, expiryDate);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean refreshQuotaAndSettleDebt(Long userId, Integer year) {
        LeaveAccount account = accountMapper.selectAccountByUserIdAndYear(userId, year);
        if (account == null) {
            return false;
        }
        SysUser user = userMapper.selectUserById(userId);
        if (user == null) {
            return false;
        }

        boolean changed = recalcQuotaFields(account, user, year);
        if (changed) {
            accountMapper.updateAccount(account);
        }

        BigDecimal settled = normalizeFloatingDebt(account, year);
        return changed || settled.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 历史透支归位。
     *
     * <p>
     * 按天累计的额度模型下, 年初请假必然产生 expiry_date 为 NULL 的透支流水;
     * 等额度累计上来之后这笔「债」其实早已不成立, 却会一直挂在账上 ——
     * 页面显示为「额度透支」, 跨年时还要参与结转冲抵, 容易重复计算。
     *
     * <p>
     * 这里在额度足以覆盖时写一对冲抵流水把它转正: 从最早到期的桶按额扣除,
     * 同时冲销等额浮动债务。总余额不变, 但债务清零、消耗落到正确的到期桶上。
     * 幂等: 归位后浮动债务为 0, 再次执行不会产生新流水。
     *
     * @return 本次实际归位的天数
     */
    private BigDecimal normalizeFloatingDebt(LeaveAccount account, int year) {
        TreeMap<LocalDate, BigDecimal> buckets = buildBuckets(account, year, LocalDate.of(year, 1, 1));

        BigDecimal floating = buckets.get(null);
        if (floating == null || floating.compareTo(BigDecimal.ZERO) >= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal debt = floating.negate();
        BigDecimal remaining = debt;
        LocalDate today = LocalDate.now();
        Long userId = account.getUserId();
        List<LeaveRecord> offsets = new ArrayList<>();

        for (Map.Entry<LocalDate, BigDecimal> entry : buckets.entrySet()) {
            LocalDate expiry = entry.getKey();
            if (expiry == null || remaining.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal credit = entry.getValue();
            if (credit.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal take = credit.min(remaining);

            LeaveRecord deduct = new LeaveRecord();
            deduct.setUserId(userId);
            deduct.setStartDate(today);
            deduct.setEndDate(today);
            deduct.setDays(take.negate());
            deduct.setType("ADJUSTMENT_DEDUCT");
            deduct.setExpiryDate(expiry);
            deduct.setRemarks(String.format("透支归位: 由额度承接 (过期: %s)", expiry));
            deduct.setCreateTime(LocalDateTime.now());
            offsets.add(deduct);

            remaining = remaining.subtract(take);
        }

        BigDecimal settled = debt.subtract(remaining);
        if (settled.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        offsets.forEach(recordMapper::insertRecord);

        LeaveRecord clear = new LeaveRecord();
        clear.setUserId(userId);
        clear.setStartDate(today);
        clear.setEndDate(today);
        clear.setDays(settled);
        clear.setType("ADJUSTMENT_ADD");
        clear.setExpiryDate(null);
        clear.setRemarks(String.format("透支归位: 冲销历史透支 %s 天", settled.stripTrailingZeros().toPlainString()));
        clear.setCreateTime(LocalDateTime.now());
        recordMapper.insertRecord(clear);

        log.info("♻️  Normalized {} day(s) of floating debt for user {} (year {})", settled, userId, year);
        return settled;
    }

    @Override
    public LeaveAccountDTO getAccount(Long userId, Integer year) {
        return fillAccountDTO(new LeaveAccountDTO(), userId, year);
    }

    @Override
    public List<LeaveAccountDTO> getAllAccounts(Integer year) {
        List<SysUser> users = userMapper.selectActiveUsers();
        String lastSyncTime = resolveLastSyncTime();
        return users.stream()
                .map(user -> fillAccountDTO(new LeaveAccountDTO(), user.getId(), year, lastSyncTime))
                .collect(Collectors.toList());
    }

    @Override
    public Page<LeaveAccountDTO> getAllAccountsPage(Integer year, int current, int size) {
        // Filter out resigned users by default (implemented in XML)
        Page<SysUser> userPage = userMapper.selectActiveUsersPage(new Page<>(current, size));
        Page<LeaveAccountDTO> resultPage = new Page<>(current, size);
        resultPage.setTotal(userPage.getTotal());

        String lastSyncTime = resolveLastSyncTime();
        List<LeaveAccountDTO> dtoList = userPage.getRecords().stream()
                .map(user -> fillAccountDTO(new LeaveAccountDTO(), user.getId(), year, lastSyncTime))
                .collect(Collectors.toList());

        resultPage.setRecords(dtoList);
        return resultPage;
    }

    /**
     * 查询钉钉同步任务的上次执行时间。列表接口按用户逐行填充 DTO, 这个值对所有行都一样,
     * 必须在循环外查一次 —— 旧实现每个用户都全表扫一遍 sys_job。
     */
    private String resolveLastSyncTime() {
        try {
            return jobMapper.selectAllJobs().stream()
                    .filter(j -> j.getInvokeTarget() != null && j.getInvokeTarget().contains("syncLeaveData"))
                    .map(SysJob::getLastRunTime)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .map(t -> t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    .orElse("暂无同步记录");
        } catch (Exception e) {
            log.error("Failed to fetch last sync time", e);
            return "获取失败";
        }
    }

    private LeaveAccountDTO fillAccountDTO(LeaveAccountDTO dto, Long userId, Integer year) {
        return fillAccountDTO(dto, userId, year, resolveLastSyncTime());
    }

    private LeaveAccountDTO fillAccountDTO(LeaveAccountDTO dto, Long userId, Integer year, String lastSyncTime) {
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

        dto.setLastSyncTime(lastSyncTime);

        // Only query existing account, DO NOT auto-initialize
        // Initialization should only happen through scheduled tasks or manual execution
        LeaveAccount account = accountMapper.selectAccountByUserIdAndYear(userId, year);

        // 当年额度按天累计, 展示前先在内存里补算到今天。
        // 这里刻意不落库: 读接口不应该写数据库(列表一页就是 10 次写, 还没有事务),
        // 库里的值由每日任务 refreshCurrentYearQuota 负责保持新鲜。
        if (account != null && year == LocalDate.now().getYear()) {
            recalcQuotaFields(account, user, year);
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

            // 余额 = 桶账本各桶之和 (含浮动债务这个负数桶)。
            // 与 deductLeaveDays 共用 buildBuckets, 保证「页面显示的余额」和
            // 「扣减时判定的可用额度」永远是同一个数 —— 旧实现两边各算各的, 会对不上。
            dto.setTotalBalance(sumBuckets(buildBuckets(account, year, LocalDate.of(year, 1, 1))));

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
    @Transactional
    public void updateRecord(LeaveRecord record) {
        recordMapper.updateRecord(record);
    }

    @Override
    @Transactional
    public void addRecord(LeaveRecord record) {
        // 入参校验: 这些字段缺失会一路走到 deductLeaveDays 里空指针,
        // 而前端新增记录行的日期默认是空字符串, 很容易触发。
        if (record.getUserId() == null) {
            throw new BusinessException("请选择员工");
        }
        if (record.getType() == null || record.getType().isBlank()) {
            throw new BusinessException("请选择记录类型");
        }
        if (!ALLOWED_MANUAL_TYPES.contains(record.getType())) {
            throw new BusinessException("不支持的记录类型: " + record.getType());
        }
        if (record.getStartDate() == null) {
            throw new BusinessException("请填写开始日期");
        }
        if (record.getEndDate() == null) {
            record.setEndDate(record.getStartDate());
        }
        if (record.getEndDate().isBefore(record.getStartDate())) {
            throw new BusinessException("结束日期不能早于开始日期");
        }
        if (record.getDays() == null || record.getDays().compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException("天数不能为空且不能为 0");
        }

        if (record.getCreateTime() == null) {
            record.setCreateTime(LocalDateTime.now());
        }

        // 天数一律按绝对值处理, 符号由类型决定 (页面上填的都是正数)
        BigDecimal absDays = record.getDays().abs();

        // 扣减类记录必须走优先级扣减逻辑, 才能正确落到到期桶上
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