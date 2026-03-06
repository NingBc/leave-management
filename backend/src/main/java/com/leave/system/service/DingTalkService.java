package com.leave.system.service;

public interface DingTalkService {
    /**
     * Sync leave data from DingTalk for the last week.
     */
    void syncLeaveData();

    /**
     * Get DingTalk userid by auth code (SSO).
     * 
     * @param authCode The temporary auth code from frontend JSAPI
     * @return DingTalk userid
     */
    String getUseridByAuthCode(String authCode);

    /**
     * Sync local leave balance to DingTalk (Full Sync).
     */
    void syncToDingTalk();

    /**
     * Sync local leave balance to DingTalk for a single user (Real-time Sync).
     * 
     * @param userId Local user ID
     */
    void syncToDingTalk(Long userId);

    /**
     * List all vacation types from DingTalk for discovery.
     * 
     * @return JSON string of vacation types
     */
    String listVacationTypes();
}
