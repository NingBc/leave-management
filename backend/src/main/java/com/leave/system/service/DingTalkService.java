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
}
