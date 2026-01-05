package com.leave.system.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dingtalk")
public class DingTalkConfig {
    private String appKey;
    private String appSecret;
    private int syncLookbackDays = 7; // Default to 10 days

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public int getSyncLookbackDays() {
        return syncLookbackDays;
    }

    public void setSyncLookbackDays(int syncLookbackDays) {
        this.syncLookbackDays = syncLookbackDays;
    }
}
