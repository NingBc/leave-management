package com.leave.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

@Data
@TableName("leave_account")
public class LeaveAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Integer year;
    private Integer socialSeniority;
    private BigDecimal standardQuota; // 年休假天数
    private Integer daysEmployed; // 年在职天数
    private BigDecimal actualQuota; // 年假天数 (Replaces annualQuota)
    private BigDecimal lastYearBalance;
    private BigDecimal currentYearUsed;
    @com.baomidou.mybatisplus.annotation.TableLogic
    private Integer deleted;

    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private BigDecimal totalBalance; // 年假余额

    public BigDecimal getStandardQuota() {
        return standardQuota;
    }

    public BigDecimal getActualQuota() {
        return actualQuota;
    }

    public Integer getDaysEmployed() {
        return daysEmployed;
    }

    public BigDecimal getLastYearBalance() {
        return lastYearBalance;
    }

    public BigDecimal getCurrentYearUsed() {
        return currentYearUsed;
    }

    public Integer getSocialSeniority() {
        return socialSeniority;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public void setSocialSeniority(Integer socialSeniority) {
        this.socialSeniority = socialSeniority;
    }

    public void setStandardQuota(BigDecimal standardQuota) {
        this.standardQuota = standardQuota;
    }

    public void setDaysEmployed(Integer daysEmployed) {
        this.daysEmployed = daysEmployed;
    }

    public void setActualQuota(BigDecimal actualQuota) {
        this.actualQuota = actualQuota;
    }

    public void setLastYearBalance(BigDecimal lastYearBalance) {
        this.lastYearBalance = lastYearBalance;
    }

    public void setCurrentYearUsed(BigDecimal currentYearUsed) {
        this.currentYearUsed = currentYearUsed;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public void setTotalBalance(BigDecimal totalBalance) {
        this.totalBalance = totalBalance;
    }
}
