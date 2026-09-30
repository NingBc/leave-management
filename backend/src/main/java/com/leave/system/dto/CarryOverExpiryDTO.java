package com.leave.system.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 某年度上年结转的作废汇总: 到作废日还有几天, 有多少在职员工、共多少天结转还没用掉。
 *
 * <p>
 * 管理页据此提醒「该催谁休假」。人数和天数按全员统计 —— 列表是分页的,
 * 前端只拿得到一页, 自己汇总会漏人。
 */
public class CarryOverExpiryDTO {

    /** 作废日, 即该年度 12 月 31 日 */
    private LocalDate expiryDate;

    /** 今天到作废日还有几天; 已过作废日为负数 */
    private long daysLeft;

    /** 结转还有剩余的在职员工人数 */
    private int userCount;

    /** 这些人剩余结转之和 */
    private BigDecimal totalDays;

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public long getDaysLeft() {
        return daysLeft;
    }

    public void setDaysLeft(long daysLeft) {
        this.daysLeft = daysLeft;
    }

    public int getUserCount() {
        return userCount;
    }

    public void setUserCount(int userCount) {
        this.userCount = userCount;
    }

    public BigDecimal getTotalDays() {
        return totalDays;
    }

    public void setTotalDays(BigDecimal totalDays) {
        this.totalDays = totalDays;
    }
}
