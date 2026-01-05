package com.leave.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leave.system.dto.LeaveAccountDTO;
import com.leave.system.entity.LeaveAccount;
import com.leave.system.entity.LeaveRecord;
import java.time.LocalDate;
import java.util.List;

public interface LeaveService {
    /**
     * Initialize or Update Leave Account for a user for a specific year.
     * Calculates quota based on seniority.
     */
    LeaveAccount initYearlyAccount(Long userId, Integer year);

    /**
     * Apply for annual leave.
     * Automatically deducts from Last Year's balance first if applicable.
     */
    void applyLeave(Long userId, LocalDate startDate, LocalDate endDate);

    /**
     * Get leave account details for a user.
     */
    com.leave.system.dto.LeaveAccountDTO getAccount(Long userId, Integer year);

    List<com.leave.system.dto.LeaveAccountDTO> getAllAccounts(Integer year);

    com.baomidou.mybatisplus.extension.plugins.pagination.Page<LeaveAccountDTO> getAllAccountsPage(Integer year,
            int current, int size);

    /**
     * Get leave history for a user, optionally filtered by year.
     */
    List<LeaveRecord> getHistory(Long userId, Integer year);

    /**
     * Get all leave records.
     */
    List<LeaveRecord> getAllRecords();

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
     * Soft delete all leave accounts for a user.
     */
    void deleteAccountsByUserId(Long userId);

    /**
     * Get all years that have leave account records
     * 
     * @return List of years with existing leave accounts
     */
    List<Integer> getAllAvailableYears();

    List<com.leave.system.entity.SysUser> getAllUsers();
}
