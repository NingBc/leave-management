package com.leave.system.dto;

import com.leave.system.entity.LeaveAccount;
import lombok.Data;

@Data
public class LeaveAccountDTO extends LeaveAccount {
    private String username;
    private String realName;
    private String employeeNumber;
    private java.time.LocalDate entryDate;
    private java.util.List<com.leave.system.entity.LeaveRecord> records;

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

    public java.time.LocalDate getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(java.time.LocalDate entryDate) {
        this.entryDate = entryDate;
    }

    public java.util.List<com.leave.system.entity.LeaveRecord> getRecords() {
        return records;
    }

    public void setRecords(java.util.List<com.leave.system.entity.LeaveRecord> records) {
        this.records = records;
    }
}
