package com.leave.system.service;

import com.leave.system.dto.LeaveAccountDTO;
import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.LeaveAccountMapper;
import com.leave.system.mapper.LeaveRecordMapper;
import com.leave.system.mapper.SysJobMapper;
import com.leave.system.mapper.SysUserMapper;
import com.leave.system.service.impl.LeaveServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 首页「年底满 N 天」的 N —— {@code yearEndQuota}: 年底 (12/31, 已离职则到离职日) 能累积到的额度。
 *
 * <p>
 * 之前页面拿 standardQuota (整年在职的档位额度) 当这个数, 7 月 1 日入职的 5 天档员工看到「年底满 5」,
 * 而年终结算只会给他 2.5。
 *
 * <p>
 * 预告值只取决于「用户 + 年度」, 与今天无关, 所以用当年做测试年度不会随运行日期漂移
 * (只有当年才预告, 往年已算定)。日期都写成相对当年的, 期望值也挑了平年、闰年结果一致的:
 * 5 天档 7/1 入职, 平年 5×184/365=2.52、闰年 5×184/366=2.51, 向下取 0.5 都是 2.5。
 */
class LeaveYearEndQuotaTest {

    private static final long USER_ID = 7L;

    private final int year = LocalDate.now().getYear();

    private LeaveAccountMapper accountMapper;
    private SysUserMapper userMapper;
    private LeaveServiceImpl service;
    private SysUser user;

    @BeforeEach
    void setUp() {
        accountMapper = mock(LeaveAccountMapper.class);
        LeaveRecordMapper recordMapper = mock(LeaveRecordMapper.class);
        userMapper = mock(SysUserMapper.class);
        SysJobMapper jobMapper = mock(SysJobMapper.class);
        service = new LeaveServiceImpl(accountMapper, recordMapper, userMapper, jobMapper);

        when(jobMapper.selectAllJobs()).thenReturn(Collections.emptyList());
        when(recordMapper.selectRecordsByYear(anyLong(), any())).thenReturn(Collections.emptyList());
        when(recordMapper.selectLedgerRecords(anyLong(), any(), any())).thenReturn(Collections.emptyList());

        user = new SysUser();
        user.setId(USER_ID);
        user.setUsername("user" + USER_ID);
        when(userMapper.selectUserById(USER_ID)).thenReturn(user);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** 累计工龄 n 年的首次参加工作日: 当年 1/1 起算满 n 年, 整个当年内工龄档都不会变 */
    private LocalDate workedFor(int years) {
        return LocalDate.of(year - years, 1, 1);
    }

    private void givenUser(LocalDate firstWorkDate, LocalDate entryDate, LocalDate resignationDate) {
        user.setFirstWorkDate(firstWorkDate);
        user.setEntryDate(entryDate);
        user.setResignationDate(resignationDate);
    }

    /** 账户上的 actual_quota 是陈旧值, 当年展示前会被重算覆盖; 往年则原样使用 */
    private LeaveAccount givenAccount(int accountYear, String storedActualQuota) {
        LeaveAccount account = new LeaveAccount();
        account.setId(USER_ID);
        account.setUserId(USER_ID);
        account.setYear(accountYear);
        account.setLastYearBalance(BigDecimal.ZERO);
        account.setActualQuota(new BigDecimal(storedActualQuota));
        account.setStandardQuota(new BigDecimal("5.0"));
        account.setDaysEmployed(0);
        account.setSocialSeniority(0);
        when(accountMapper.selectAccountByUserIdAndYear(USER_ID, accountYear)).thenReturn(account);
        return account;
    }

    private LeaveAccountDTO currentYearAccount(LocalDate firstWorkDate, LocalDate entryDate,
            LocalDate resignationDate) {
        givenUser(firstWorkDate, entryDate, resignationDate);
        givenAccount(year, "0");
        return service.getAccount(USER_ID, year);
    }

    private static void assertDays(String expected, BigDecimal actual, String what) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> what + ": 期望 " + expected + ", 实际 " + actual);
    }

    // ------------------------------------------------------------------
    // 年中入职 / 整年在职
    // ------------------------------------------------------------------

    @Test
    @DisplayName("年中入职: 年底满不了整年应享, 只满按入职后天数折算的数")
    void midYearHireDoesNotReachStandardQuota() {
        // 7/1 入职, 年底在职 184 天; 5 天档 → 5 × 184/365 = 2.52 → 向下取整 2.5
        LeaveAccountDTO dto = currentYearAccount(workedFor(3), LocalDate.of(year, 7, 1), null);

        assertDays("2.5", dto.getYearEndQuota(), "年底满");
        assertDays("5.0", dto.getStandardQuota(), "全年应享 (档位额度不受影响)");
        assertTrue(dto.getYearEndQuota().compareTo(dto.getStandardQuota()) < 0,
                "年中入职的人, 年底满必须小于全年应享");
    }

    @Test
    @DisplayName("整年在职: 年底满 = 全年应享")
    void fullYearEmployeeReachesStandardQuota() {
        LeaveAccountDTO dto = currentYearAccount(workedFor(12), LocalDate.of(year - 2, 4, 1), null);

        assertDays("10.0", dto.getYearEndQuota(), "年底满");
        assertDays("10.0", dto.getStandardQuota(), "全年应享");
    }

    @Test
    @DisplayName("12 月才入职: 年底也累积不到半天, 不能再预告整年额度")
    void lateYearHireGetsNothing() {
        // 12/1 入职, 年底在职 31 天; 5 × 31/365 = 0.42 → 向下取整 0
        LeaveAccountDTO dto = currentYearAccount(workedFor(3), LocalDate.of(year, 12, 1), null);

        assertDays("0", dto.getYearEndQuota(), "年底满");
    }

    @Test
    @DisplayName("当年离职 (含已排定的离职日): 只算到离职日")
    void resignationCutsTheProjection() {
        // 整年在职、8/31 离职: 1/1~8/31 共 243 天 (闰年 244); 10 × 243/365 = 6.66 → 6.5
        LeaveAccountDTO dto = currentYearAccount(workedFor(12), LocalDate.of(year - 3, 1, 1),
                LocalDate.of(year, 8, 31));

        assertDays("6.5", dto.getYearEndQuota(), "年底满");
    }

    // ------------------------------------------------------------------
    // 工龄档位: 按年底 (与年终结算一致) 判定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("年内满 10 年: 年底满按新档位算 (年终结算就是按 12/31 判档), 不用等到那天才改口")
    void tierUpgradeBeforeYearEndIsIncluded() {
        // 11/3 满 10 年: 11/3 之前 standardQuota 还是 5, 但年终结算会按 10 天档给整年
        LeaveAccountDTO dto = currentYearAccount(LocalDate.of(year - 10, 11, 3),
                LocalDate.of(year - 5, 1, 1), null);

        assertDays("10.0", dto.getYearEndQuota(), "年底满");
    }

    @Test
    @DisplayName("满 10 年恰好落在 12/31 算数, 晚一天就不算")
    void seniorityIsJudgedAtYearEnd() {
        LocalDate entry = LocalDate.of(year - 5, 1, 1);

        // year-10 的 12/31 参加工作 → 当年 12/31 正好满 10 年
        assertDays("10.0", currentYearAccount(LocalDate.of(year - 10, 12, 31), entry, null).getYearEndQuota(),
                "12/31 满 10 年");
        // 次日参加工作 → 当年 12/31 差一天, 仍是 9 年
        assertDays("5.0", currentYearAccount(LocalDate.of(year - 9, 1, 1), entry, null).getYearEndQuota(),
                "差一天满 10 年");
    }

    // ------------------------------------------------------------------
    // 离职: 离职之后的日子不再计入 (在职天数和工龄档位用同一个截止日)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("离职早于满 10 年那天: 判档只算到离职日, 不会因为 12/31 才满而按 10 天档算")
    void resignedBeforeTheAnniversaryStaysInTheLowerTier() {
        // 8/31 离职、11/3 才满 10 年: 5 天档 × 243/365 = 3.33 → 3.0 (若判档判到 12/31 会是 10 天档 → 6.5)
        LeaveAccountDTO dto = currentYearAccount(LocalDate.of(year - 10, 11, 3),
                LocalDate.of(year - 5, 1, 1), LocalDate.of(year, 8, 31));

        assertDays("3.0", dto.getYearEndQuota(), "年底满");
    }

    @Test
    @DisplayName("离职当天恰好满 10 年算数, 前一天离职就不算")
    void anniversaryOnTheResignationDayCounts() {
        LocalDate firstWork = LocalDate.of(year - 10, 11, 3);
        LocalDate entry = LocalDate.of(year - 5, 1, 1);

        // 11/3 离职且当天满 10 年: 10 天档 × 307/365 = 8.41 → 8.0 (闰年 308/366 同为 8.0)
        assertDays("8.0", currentYearAccount(firstWork, entry, LocalDate.of(year, 11, 3)).getYearEndQuota(),
                "当天满 10 年");
        // 11/2 离职: 5 天档 × 306/365 = 4.19 → 4.0 (闰年 307/366 同为 4.0)
        assertDays("4.0", currentYearAccount(firstWork, entry, LocalDate.of(year, 11, 2)).getYearEndQuota(),
                "前一天离职");
    }

    @Test
    @DisplayName("年终任务重新结算离职人员的离职年度: 额度不会被改大, 离职当天算定的就是最终值")
    void yearEndSettlementDoesNotUpgradeAResignedEmployee() {
        // 用往年做结算年度: 年终任务的参照日固定是该年 12/31, 不依赖今天
        int settledYear = year - 1;
        givenUser(LocalDate.of(settledYear - 10, 11, 3), LocalDate.of(settledYear - 5, 1, 1),
                LocalDate.of(settledYear, 8, 31));
        LeaveAccount account = givenAccount(settledYear, "0");

        service.settleYearQuota(USER_ID, settledYear);

        assertEquals(9, account.getSocialSeniority(), "离职时累计工龄 9 年");
        assertDays("5.0", account.getStandardQuota(), "档位");
        assertDays("3.0", account.getActualQuota(), "结算额度");
    }

    // ------------------------------------------------------------------
    // 当年之外 / 没有账户
    // ------------------------------------------------------------------

    @Test
    @DisplayName("往年已经算定: 年底满取账户上的实际额度, 不再重算")
    void pastYearUsesSettledQuota() {
        // 若被重算, 这位员工整年在职 5 天档会得到 5.0; 账户上算定的是 8.0
        givenUser(workedFor(3), LocalDate.of(year - 5, 1, 1), null);
        givenAccount(year - 1, "8.0");

        LeaveAccountDTO dto = service.getAccount(USER_ID, year - 1);

        assertDays("8.0", dto.getYearEndQuota(), "往年年底满");
    }

    @Test
    @DisplayName("账户还没建立: 年底满为 0, 页面走「建立账户」那一支")
    void noAccountYieldsZero() {
        givenUser(workedFor(3), LocalDate.of(year - 5, 1, 1), null);
        when(accountMapper.selectAccountByUserIdAndYear(USER_ID, year)).thenReturn(null);

        LeaveAccountDTO dto = service.getAccount(USER_ID, year);

        assertDays("0", dto.getYearEndQuota(), "年底满");
    }

    // ------------------------------------------------------------------
    // 对拍
    // ------------------------------------------------------------------

    /**
     * 全年每一天都当一次入职日, 其中晚于今天的入职日 (账户提前建好、今天累积为 0) 也在内。
     */
    @Test
    @DisplayName("逐日扫描入职日 × 三个档位: 与独立算式逐个一致, 且年底满不小于当前已累积")
    void sweepEveryEntryDate() {
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        int daysInYear = yearEnd.getDayOfYear();
        givenAccount(year, "0");

        for (int seniority : new int[] { 3, 12, 22 }) {
            int tier = seniority < 10 ? 5 : seniority < 20 ? 10 : 15;

            for (LocalDate entry = LocalDate.of(year, 1, 1); !entry.isAfter(yearEnd); entry = entry.plusDays(1)) {
                givenUser(workedFor(seniority), entry, null);
                LeaveAccountDTO dto = service.getAccount(USER_ID, year);

                // 独立算式: 标准额度 × 年底在职天数 / 全年天数, 向下取整到 0.5 (整数运算, 无浮点误差)
                long days = ChronoUnit.DAYS.between(entry, yearEnd) + 1;
                double expected = Math.floorDiv(2L * tier * days, (long) daysInYear) / 2.0;
                String where = "入职 " + entry + ", " + tier + " 天档";

                assertEquals(expected, dto.getYearEndQuota().doubleValue(), 0.0, where + ": 年底满");
                assertTrue(dto.getYearEndQuota().compareTo(dto.getActualQuota()) >= 0,
                        where + ": 年底满 " + dto.getYearEndQuota() + " 不应小于已累积 " + dto.getActualQuota());
            }
        }
    }

    /**
     * 11/3 满 10 年的整年在职员工, 全年每一天都当一次离职日 (含晚于今天、已排定的离职日)。
     */
    @Test
    @DisplayName("逐日扫描离职日: 与独立算式逐个一致 (离职当天满 10 年算数, 之后的日子一概不计)")
    void sweepEveryResignationDate() {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        LocalDate anniversary = LocalDate.of(year, 11, 3);
        int daysInYear = yearEnd.getDayOfYear();
        givenAccount(year, "0");

        for (LocalDate resign = yearStart; !resign.isAfter(yearEnd); resign = resign.plusDays(1)) {
            givenUser(LocalDate.of(year - 10, 11, 3), LocalDate.of(year - 5, 1, 1), resign);
            LeaveAccountDTO dto = service.getAccount(USER_ID, year);

            int tier = resign.isBefore(anniversary) ? 5 : 10;
            long days = ChronoUnit.DAYS.between(yearStart, resign) + 1;
            double expected = Math.floorDiv(2L * tier * days, (long) daysInYear) / 2.0;

            assertEquals(expected, dto.getYearEndQuota().doubleValue(), 0.0, "离职 " + resign + ": 年底满");
        }
    }
}
