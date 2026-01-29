package com.leave.system.dto;

import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import lombok.Data;

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
