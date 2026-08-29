package com.leave.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

@Data
@TableName("leave_account")
public class LeaveAccount {
    // 注意: 本类的 @Data 不会生成任何方法(项目里 Lombok 注解处理未生效),
    // 新增字段必须手写 getter/setter, 否则 Jackson 不会序列化。
    //
    // 「本年已用」「年假余额」是派生值, 不再落库(见 LeaveAccountDTO):
    // 它们只能由流水实时算出, 曾经的 current_year_used 列几乎从不更新,
    // 连带 MySQL 生成列 total_balance 全线失真(最多偏高 8.5 天)。
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Integer year;
    private Integer socialSeniority;
    private BigDecimal standardQuota; // 年休假天数
    private Integer daysEmployed; // 年在职天数
    private BigDecimal actualQuota; // 年假天数 (Replaces annualQuota)
    private BigDecimal lastYearBalance;
    @com.baomidou.mybatisplus.annotation.TableLogic
    private Integer deleted;

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

    public Integer getSocialSeniority() {
        return socialSeniority;
    }

    public Long getUserId() {
        return userId;
    }

    public Integer getYear() {
        return year;
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

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public Integer getDeleted() {
        return deleted;
    }


}
