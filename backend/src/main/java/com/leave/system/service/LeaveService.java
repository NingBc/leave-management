package com.leave.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leave.system.dto.LeaveAccountDTO;
import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.leave.system.entity.SysUser;

public interface LeaveService {
    /**
     * Initialize or Update Leave Account for a user for a specific year.
     * Calculates quota based on seniority.
     */
    LeaveAccount initYearlyAccount(Long userId, Integer year);

    /**
     * Refresh account using provided user object (avoids DB fetch latency/cache
     * issues)
     */
    LeaveAccount refreshAccount(SysUser user, Integer year);

    void applyLeave(Long userId, LocalDate startDate, LocalDate endDate);

    /**
     * Apply for annual leave with specific days.
     */
    void applyLeave(Long userId, LocalDate startDate, LocalDate endDate, BigDecimal daysRequested);

    /**
     * 年度结算: 按入职/离职日期算定该年度的在职天数与实际额度并落库,
     * 同时把已能被额度覆盖的历史透支归位。
     *
     * <p>
     * 当年额度是逐日累计的移动靶, 库里的值平时没有意义; 只有在该年度结算时
     * (年终清理, 或员工离职) 才需要把它算定成「这一年最终给了多少天」。
     *
     * @return 是否产生了变更
     */
    boolean settleYearQuota(Long userId, Integer year);

    /**
     * Get leave account details for a user.
     */
    LeaveAccountDTO getAccount(Long userId, Integer year);

    List<LeaveAccountDTO> getAllAccounts(Integer year);

    Page<LeaveAccountDTO> getAllAccountsPage(Integer year, int current, int size);

    /**
     * Get leave history for a user, optionally filtered by year.
     */
    List<LeaveRecord> getHistory(Long userId, Integer year);

    /**
     * Update an existing leave record.
     */
    void updateRecord(LeaveRecord record);

    /**
     * Add a new leave record manually.
     */
    void addRecord(LeaveRecord record);

    void updateAccount(LeaveAccount account);

    /**
     * 员工离职结算。
     *
     * <p>
     * 按离职日期算定离职当年的最终额度, 并只清理<b>离职年度之后</b>的账户 ——
     * 当年及历史年度必须保留, 否则既没有结算依据, 也查不到这个人当年有多少天。
     */
    void settleResignation(Long userId, java.time.LocalDate resignationDate);

    /**
     * Get all years that have leave account records
     * 
     * @return List of years with existing leave accounts
     */
    List<Integer> getAllAvailableYears();
}
