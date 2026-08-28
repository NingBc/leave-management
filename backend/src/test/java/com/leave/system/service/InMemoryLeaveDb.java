package com.leave.system.service;

import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.LeaveAccountMapper;
import com.leave.system.mapper.LeaveRecordMapper;
import com.leave.system.mapper.SysJobMapper;
import com.leave.system.mapper.SysUserMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 内存假库: 按 mapper XML 的语义实现各条查询, 用来跑跨年度的完整链路。
 *
 * <p>
 * <b>刻意做成读写都复制对象</b>。如果直接返回内部实例, 业务代码 mutate 了对象却忘了调
 * updateAccount, 测试也照样通过 —— 那正是这次要防的一类 bug。复制之后, 只有真正
 * 写回数据库的改动才可见, 与真实的 MyBatis 行为一致。
 *
 * <p>
 * update 语句同样复刻 XML 的 {@code <if test="xxx != null">} 语义: 只更新非空字段,
 * 所以 expiry_date 无法通过 update 置空 —— 这个限制也要能被测出来。
 */
class InMemoryLeaveDb {

    private final List<SysUser> users = new ArrayList<>();
    private final List<LeaveAccount> accounts = new ArrayList<>();
    private final List<LeaveRecord> records = new ArrayList<>();
    private final AtomicLong accountSeq = new AtomicLong(1);
    private final AtomicLong recordSeq = new AtomicLong(1);

    final LeaveAccountMapper accountMapper = mock(LeaveAccountMapper.class);
    final LeaveRecordMapper recordMapper = mock(LeaveRecordMapper.class);
    final SysUserMapper userMapper = mock(SysUserMapper.class);
    final SysJobMapper jobMapper = mock(SysJobMapper.class);

    InMemoryLeaveDb() {
        wireUserMapper();
        wireAccountMapper();
        wireRecordMapper();
        when(jobMapper.selectAllJobs()).thenReturn(Collections.emptyList());
    }

    // ------------------------------------------------------------------
    // 装载数据
    // ------------------------------------------------------------------

    SysUser addUser(long id, String name, LocalDate firstWorkDate, LocalDate entryDate) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(name);
        u.setRealName(name);
        u.setStatus("ACTIVE");
        u.setDeleted(0);
        u.setFirstWorkDate(firstWorkDate);
        u.setEntryDate(entryDate);
        users.add(u);
        return u;
    }

    /** 直接落一个年度账户, 模拟历史数据 */
    LeaveAccount seedAccount(long userId, int year, String standardQuota, String actualQuota,
            String lastYearBalance, int daysEmployed) {
        LeaveAccount a = new LeaveAccount();
        a.setId(accountSeq.getAndIncrement());
        a.setUserId(userId);
        a.setYear(year);
        a.setStandardQuota(new BigDecimal(standardQuota));
        a.setActualQuota(new BigDecimal(actualQuota));
        a.setLastYearBalance(new BigDecimal(lastYearBalance));
        a.setDaysEmployed(daysEmployed);
        a.setSocialSeniority(0);
        a.setDeleted(0);
        accounts.add(a);
        return copy(a);
    }

    LeaveRecord seedRecord(long userId, String type, String days, LocalDate expiry,
            LocalDate startDate, LocalDateTime createTime) {
        LeaveRecord r = new LeaveRecord();
        r.setId(recordSeq.getAndIncrement());
        r.setUserId(userId);
        r.setType(type);
        r.setDays(new BigDecimal(days));
        r.setExpiryDate(expiry);
        r.setStartDate(startDate);
        r.setEndDate(startDate);
        r.setCreateTime(createTime);
        r.setDeleted(0);
        records.add(r);
        return copy(r);
    }

    // ------------------------------------------------------------------
    // 查询快照 (给断言用)
    // ------------------------------------------------------------------

    LeaveAccount account(long userId, int year) {
        return accounts.stream()
                .filter(a -> a.getUserId() == userId && a.getYear() == year && notDeleted(a.getDeleted()))
                .findFirst().map(InMemoryLeaveDb::copy).orElse(null);
    }

    List<LeaveRecord> allRecords(long userId) {
        return records.stream()
                .filter(r -> r.getUserId() == userId && notDeleted(r.getDeleted()))
                .sorted(Comparator.comparing(LeaveRecord::getId))
                .map(InMemoryLeaveDb::copy)
                .collect(Collectors.toList());
    }

    /** 全部流水净额 (发放为正, 使用/过期为负) */
    BigDecimal netRecords(long userId) {
        return allRecords(userId).stream().map(LeaveRecord::getDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    BigDecimal sumRecords(long userId, String type) {
        return allRecords(userId).stream()
                .filter(r -> type.equals(r.getType()))
                .map(LeaveRecord::getDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    int recordCount(long userId) {
        return allRecords(userId).size();
    }

    // ------------------------------------------------------------------
    // mapper 行为
    // ------------------------------------------------------------------

    private void wireUserMapper() {
        when(userMapper.selectUserById(anyLong())).thenAnswer(c -> {
            Long id = c.getArgument(0);
            return users.stream().filter(u -> u.getId().equals(id)).findFirst()
                    .map(InMemoryLeaveDb::copy).orElse(null);
        });
        when(userMapper.selectAllUsers()).thenAnswer(c -> users.stream()
                .filter(u -> notDeleted(u.getDeleted()))
                .map(InMemoryLeaveDb::copy).collect(Collectors.toList()));
        when(userMapper.selectActiveUsers()).thenAnswer(c -> users.stream()
                .filter(u -> notDeleted(u.getDeleted()) && !"RESIGNED".equals(u.getStatus()))
                .map(InMemoryLeaveDb::copy).collect(Collectors.toList()));
    }

    private void wireAccountMapper() {
        when(accountMapper.selectAccountByUserIdAndYear(anyLong(), any())).thenAnswer(c ->
                findAccount(c.getArgument(0), c.getArgument(1), false));

        when(accountMapper.selectAccountByUserIdAndYearIncludeDeleted(anyLong(), any())).thenAnswer(c ->
                findAccount(c.getArgument(0), c.getArgument(1), true));

        when(accountMapper.selectLastYearAccount(anyLong(), any())).thenAnswer(c -> {
            Integer year = c.getArgument(1);
            return findAccount(c.getArgument(0), year - 1, false);
        });

        when(accountMapper.selectAccountsByYear(any())).thenAnswer(c -> {
            Integer year = c.getArgument(0);
            return accounts.stream()
                    .filter(a -> a.getYear().equals(year) && notDeleted(a.getDeleted()))
                    .map(InMemoryLeaveDb::copy).collect(Collectors.toList());
        });

        when(accountMapper.insertAccount(any())).thenAnswer(c -> {
            LeaveAccount in = c.getArgument(0);
            LeaveAccount stored = copy(in);
            stored.setId(accountSeq.getAndIncrement());
            if (stored.getDeleted() == null) {
                stored.setDeleted(0);
            }
            accounts.add(stored);
            in.setId(stored.getId()); // useGeneratedKeys
            return 1;
        });

        when(accountMapper.updateAccount(any())).thenAnswer(c -> {
            LeaveAccount in = c.getArgument(0);
            LeaveAccount stored = accounts.stream()
                    .filter(a -> Objects.equals(a.getId(), in.getId())).findFirst().orElse(null);
            if (stored == null) {
                return 0; // WHERE id = null 时什么都不会更新, 与真实 SQL 一致
            }
            // 复刻 <if test="xxx != null"> —— 只更新非空字段
            if (in.getUserId() != null) stored.setUserId(in.getUserId());
            if (in.getYear() != null) stored.setYear(in.getYear());
            if (in.getSocialSeniority() != null) stored.setSocialSeniority(in.getSocialSeniority());
            if (in.getStandardQuota() != null) stored.setStandardQuota(in.getStandardQuota());
            if (in.getDaysEmployed() != null) stored.setDaysEmployed(in.getDaysEmployed());
            if (in.getActualQuota() != null) stored.setActualQuota(in.getActualQuota());
            if (in.getLastYearBalance() != null) stored.setLastYearBalance(in.getLastYearBalance());
            if (in.getDeleted() != null) stored.setDeleted(in.getDeleted());
            return 1;
        });

        doAnswerDeleteAfterYear();
    }

    private void doAnswerDeleteAfterYear() {
        org.mockito.Mockito.doAnswer(c -> {
            Long userId = c.getArgument(0);
            Integer year = c.getArgument(1);
            accounts.stream()
                    .filter(a -> a.getUserId().equals(userId) && a.getYear() > year)
                    .forEach(a -> a.setDeleted(1));
            return null;
        }).when(accountMapper).deleteAccountsAfterYear(anyLong(), any());
    }

    private void wireRecordMapper() {
        when(recordMapper.insertRecord(any())).thenAnswer(c -> {
            LeaveRecord in = c.getArgument(0);
            LeaveRecord stored = copy(in);
            stored.setId(recordSeq.getAndIncrement());
            if (stored.getDeleted() == null) {
                stored.setDeleted(0); // COALESCE(#{deleted}, 0)
            }
            records.add(stored);
            in.setId(stored.getId());
            return 1;
        });

        when(recordMapper.updateRecord(any())).thenAnswer(c -> {
            LeaveRecord in = c.getArgument(0);
            LeaveRecord stored = records.stream()
                    .filter(r -> Objects.equals(r.getId(), in.getId())).findFirst().orElse(null);
            if (stored == null) {
                return 0;
            }
            if (in.getUserId() != null) stored.setUserId(in.getUserId());
            if (in.getStartDate() != null) stored.setStartDate(in.getStartDate());
            if (in.getEndDate() != null) stored.setEndDate(in.getEndDate());
            if (in.getDays() != null) stored.setDays(in.getDays());
            if (in.getType() != null) stored.setType(in.getType());
            if (in.getRemarks() != null) stored.setRemarks(in.getRemarks());
            if (in.getCreateTime() != null) stored.setCreateTime(in.getCreateTime());
            if (in.getExpiryDate() != null) stored.setExpiryDate(in.getExpiryDate()); // 无法置空, 与 XML 一致
            if (in.getDeleted() != null) stored.setDeleted(in.getDeleted());
            return 1;
        });

        // start_date >= from AND type <> 'CARRY_OVER'
        when(recordMapper.selectLedgerRecords(anyLong(), any())).thenAnswer(c -> {
            Long userId = c.getArgument(0);
            LocalDate from = c.getArgument(1);
            return live(userId)
                    .filter(r -> !r.getStartDate().isBefore(from))
                    .filter(r -> !"CARRY_OVER".equals(r.getType()))
                    .sorted(Comparator.comparing(LeaveRecord::getStartDate).thenComparing(LeaveRecord::getId))
                    .map(InMemoryLeaveDb::copy).collect(Collectors.toList());
        });

        // YEAR(start_date) = year
        when(recordMapper.selectRecordsByYear(anyLong(), any())).thenAnswer(c -> {
            Long userId = c.getArgument(0);
            Integer year = c.getArgument(1);
            return live(userId)
                    .filter(r -> r.getStartDate().getYear() == year)
                    .sorted(Comparator.comparing(LeaveRecord::getStartDate).reversed())
                    .map(InMemoryLeaveDb::copy).collect(Collectors.toList());
        });

        when(recordMapper.selectCarryOverRecord(anyLong(), any())).thenAnswer(c -> {
            Long userId = c.getArgument(0);
            LocalDate date = c.getArgument(1);
            return live(userId)
                    .filter(r -> "CARRY_OVER".equals(r.getType()) && date.equals(r.getStartDate()))
                    .findFirst().map(InMemoryLeaveDb::copy).orElse(null);
        });

        // expiry_date = X AND type IN ('ADJUSTMENT_ADD','CARRY_OVER')
        when(recordMapper.selectExpiringRecords(anyLong(), any())).thenAnswer(c -> {
            Long userId = c.getArgument(0);
            LocalDate expiry = c.getArgument(1);
            return live(userId)
                    .filter(r -> expiry.equals(r.getExpiryDate()))
                    .filter(r -> "ADJUSTMENT_ADD".equals(r.getType()) || "CARRY_OVER".equals(r.getType()))
                    .map(InMemoryLeaveDb::copy).collect(Collectors.toList());
        });

        // type IN ('ANNUAL','ADJUSTMENT_DEDUCT') AND expiry_date = X [AND create_time >= anchor]
        when(recordMapper.selectUsageRecordsForExpiryCleanup(anyLong(), any(), any())).thenAnswer(c -> {
            Long userId = c.getArgument(0);
            LocalDate expiry = c.getArgument(1);
            LocalDateTime anchor = c.getArgument(2);
            return live(userId)
                    .filter(r -> "ANNUAL".equals(r.getType()) || "ADJUSTMENT_DEDUCT".equals(r.getType()))
                    .filter(r -> expiry.equals(r.getExpiryDate()))
                    .filter(r -> anchor == null || !r.getCreateTime().isBefore(anchor))
                    .map(InMemoryLeaveDb::copy).collect(Collectors.toList());
        });

        when(recordMapper.selectExpiredRecordsByDate(anyLong(), any())).thenAnswer(c -> {
            Long userId = c.getArgument(0);
            LocalDate date = c.getArgument(1);
            return live(userId)
                    .filter(r -> "EXPIRED".equals(r.getType()) && date.equals(r.getStartDate()))
                    .map(InMemoryLeaveDb::copy).collect(Collectors.toList());
        });

        // start_date >= from AND expiry_date IS NULL AND type != 'CARRY_OVER'
        when(recordMapper.selectFloatingRecordsForCleanup(anyLong(), any())).thenAnswer(c -> {
            Long userId = c.getArgument(0);
            LocalDate from = c.getArgument(1);
            return live(userId)
                    .filter(r -> r.getExpiryDate() == null)
                    .filter(r -> !"CARRY_OVER".equals(r.getType()))
                    .filter(r -> !r.getStartDate().isBefore(from))
                    .map(InMemoryLeaveDb::copy).collect(Collectors.toList());
        });

        when(recordMapper.selectHistory(anyLong(), any())).thenAnswer(c -> {
            Long userId = c.getArgument(0);
            Integer year = c.getArgument(1);
            return live(userId)
                    .filter(r -> year == null || r.getStartDate().getYear() == year)
                    .map(InMemoryLeaveDb::copy).collect(Collectors.toList());
        });

        when(recordMapper.sumAnnualLeaveUsage(anyLong(), any())).thenAnswer(c -> {
            Long userId = c.getArgument(0);
            LocalDate date = c.getArgument(1);
            return live(userId)
                    .filter(r -> "ANNUAL".equals(r.getType()))
                    .filter(r -> !date.isBefore(r.getStartDate()) && !date.isAfter(r.getEndDate()))
                    .map(r -> r.getDays().divide(
                            new BigDecimal(java.time.temporal.ChronoUnit.DAYS
                                    .between(r.getStartDate(), r.getEndDate()) + 1),
                            4, java.math.RoundingMode.HALF_UP))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        });
    }

    // ------------------------------------------------------------------

    private java.util.stream.Stream<LeaveRecord> live(Long userId) {
        return records.stream().filter(r -> r.getUserId().equals(userId) && notDeleted(r.getDeleted()));
    }

    private LeaveAccount findAccount(Long userId, Integer year, boolean includeDeleted) {
        return accounts.stream()
                .filter(a -> a.getUserId().equals(userId) && a.getYear().equals(year))
                .filter(a -> includeDeleted || notDeleted(a.getDeleted()))
                .findFirst().map(InMemoryLeaveDb::copy).orElse(null);
    }

    private static boolean notDeleted(Integer deleted) {
        return deleted == null || deleted == 0;
    }

    private static LeaveAccount copy(LeaveAccount a) {
        LeaveAccount c = new LeaveAccount();
        c.setId(a.getId());
        c.setUserId(a.getUserId());
        c.setYear(a.getYear());
        c.setSocialSeniority(a.getSocialSeniority());
        c.setStandardQuota(a.getStandardQuota());
        c.setDaysEmployed(a.getDaysEmployed());
        c.setActualQuota(a.getActualQuota());
        c.setLastYearBalance(a.getLastYearBalance());
        c.setDeleted(a.getDeleted());
        return c;
    }

    private static LeaveRecord copy(LeaveRecord r) {
        LeaveRecord c = new LeaveRecord();
        c.setId(r.getId());
        c.setUserId(r.getUserId());
        c.setStartDate(r.getStartDate());
        c.setEndDate(r.getEndDate());
        c.setDays(r.getDays());
        c.setType(r.getType());
        c.setRemarks(r.getRemarks());
        c.setExpiryDate(r.getExpiryDate());
        c.setCreateTime(r.getCreateTime());
        c.setDeleted(r.getDeleted());
        return c;
    }

    private static SysUser copy(SysUser u) {
        SysUser c = new SysUser();
        c.setId(u.getId());
        c.setUsername(u.getUsername());
        c.setRealName(u.getRealName());
        c.setStatus(u.getStatus());
        c.setFirstWorkDate(u.getFirstWorkDate());
        c.setEntryDate(u.getEntryDate());
        c.setResignationDate(u.getResignationDate());
        c.setEmployeeNumber(u.getEmployeeNumber());
        c.setDeleted(u.getDeleted());
        return c;
    }
}
