package com.leave.system.dto;

import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@lombok.EqualsAndHashCode(callSuper = true)
public class LeaveAccountDTO extends LeaveAccount {
    private String username;
    private String realName;
    private String employeeNumber;
    private LocalDate entryDate;
    private List<LeaveRecord> records;
    private String lastSyncTime;

    /** 本年已用: 由本年度 ANNUAL 流水实时汇总, 不落库 */
    private BigDecimal currentYearUsed;

    /** 年假余额: 由桶账本实时汇总, 不落库 */
    private BigDecimal totalBalance;

    /*
     * 余额按来源拆开, 给页面讲清楚「结转用了多少、还剩多少、哪天作废」。
     * 都取自冲抵欠账之后的桶账本, 不落库, 并且恒有:
     * carryOverRemaining + currentQuotaRemaining + floatingDebt == totalBalance
     */

    /** 上年结转还剩多少 (>= 0), carryOverExpiry 当天过后作废 */
    private BigDecimal carryOverRemaining;

    /** 上年结转的作废日, 即该年度 12 月 31 日 */
    private LocalDate carryOverExpiry;

    /** 上年结转已被年终清理作废的天数 (>= 0), 只有年度结束后才会非零 */
    private BigDecimal carryOverExpired;

    /** 当年额度 (含手工加假) 还剩多少 (>= 0) */
    private BigDecimal currentQuotaRemaining;

    /** 额度抵不完的透支 (<= 0) */
    private BigDecimal floatingDebt;

    public BigDecimal getCarryOverRemaining() {
        return carryOverRemaining;
    }

    public void setCarryOverRemaining(BigDecimal carryOverRemaining) {
        this.carryOverRemaining = carryOverRemaining;
    }

    public LocalDate getCarryOverExpiry() {
        return carryOverExpiry;
    }

    public void setCarryOverExpiry(LocalDate carryOverExpiry) {
        this.carryOverExpiry = carryOverExpiry;
    }

    public BigDecimal getCarryOverExpired() {
        return carryOverExpired;
    }

    public void setCarryOverExpired(BigDecimal carryOverExpired) {
        this.carryOverExpired = carryOverExpired;
    }

    public BigDecimal getCurrentQuotaRemaining() {
        return currentQuotaRemaining;
    }

    public void setCurrentQuotaRemaining(BigDecimal currentQuotaRemaining) {
        this.currentQuotaRemaining = currentQuotaRemaining;
    }

    public BigDecimal getFloatingDebt() {
        return floatingDebt;
    }

    public void setFloatingDebt(BigDecimal floatingDebt) {
        this.floatingDebt = floatingDebt;
    }

    public BigDecimal getCurrentYearUsed() {
        return currentYearUsed;
    }

    public void setCurrentYearUsed(BigDecimal currentYearUsed) {
        this.currentYearUsed = currentYearUsed;
    }

    public BigDecimal getTotalBalance() {
        return totalBalance;
    }

    public void setTotalBalance(BigDecimal totalBalance) {
        this.totalBalance = totalBalance;
    }

    public String getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(String lastSyncTime) {
        this.lastSyncTime = lastSyncTime;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public void setEmployeeNumber(String employeeNumber) {
        this.employeeNumber = employeeNumber;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDate entryDate) {
        this.entryDate = entryDate;
    }

    public List<LeaveRecord> getRecords() {
        return records;
    }

    public void setRecords(List<LeaveRecord> records) {
        this.records = records;
    }
}
