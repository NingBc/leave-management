package com.leave.system.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dingtalk.api.DefaultDingTalkClient;
import com.dingtalk.api.DingTalkClient;
import com.dingtalk.api.request.OapiAttendanceGetleavetimebynamesRequest;
import com.dingtalk.api.request.OapiGettokenRequest;
import com.dingtalk.api.response.OapiAttendanceGetleavetimebynamesResponse;
import com.dingtalk.api.response.OapiGettokenResponse;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.LeaveRecordMapper;
import com.leave.system.mapper.SysUserMapper;
import com.leave.system.service.DingTalkService;
import com.leave.system.config.DingTalkConfig;
import com.leave.system.config.DingTalkAppConfig;
import com.leave.system.mapper.LeaveAccountMapper;
import com.leave.system.service.LeaveService;
import com.taobao.api.ApiException;
import com.taobao.api.internal.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service("dingTalkService")
public class DingTalkServiceImpl implements DingTalkService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkServiceImpl.class);

    @Autowired
    private DingTalkConfig dingTalkConfig;

    @Autowired
    private DingTalkAppConfig dingTalkAppConfig;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private LeaveRecordMapper leaveRecordMapper;

    @Autowired
    private LeaveAccountMapper leaveAccountMapper;

    @Autowired
    private LeaveService leaveService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncLeaveData() {
        log.info("Starting DingTalk leave data sync...");
        try {
            String accessToken = getAccessToken();
            log.info("AccessToken fetched successfully.");

            // Calculate date range: based on lookback days from config
            LocalDate today = LocalDate.now();
            int lookbackDays = dingTalkConfig.getSyncLookbackDays();
            LocalDate lastWeek = today.minusDays(lookbackDays);
            log.info("Sync lookback period: {} days", lookbackDays);

            String fromDateStr = lastWeek.atStartOfDay().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String toDateStr = today.atTime(23, 59, 59).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            log.info("Syncing data from {} to {}", fromDateStr, toDateStr);

            // Get users with dingtalkUserId
            List<SysUser> users = userMapper.selectUsersWithDingtalkId();
            if (users.isEmpty()) {
                log.info("No users with DingTalk ID found.");
                return;
            }
            log.info("Found {} users to sync.", users.size());

            for (SysUser user : users) {
                log.info("Fetching data for user: {} (DingTalk ID: {})", user.getUsername(), user.getDingtalkUserId());
                fetchForSingleUser(accessToken, fromDateStr, toDateStr, user);
            }

            log.info("DingTalk leave data sync completed.");
        } catch (Exception e) {
            log.error("Error syncing DingTalk data", e);
        }
    }

    @Override
    public String getUseridByAuthCode(String authCode) {
        log.info("Exchanging authCode for DingTalk userid using App Config...");
        try {
            // Use DingTalkAppConfig instead of DingTalkConfig for SSO
            String accessToken = getAccessTokenForApp();
            DingTalkClient client = new DefaultDingTalkClient("https://oapi.dingtalk.com/topapi/v2/user/getuserinfo");
            com.dingtalk.api.request.OapiV2UserGetuserinfoRequest req = new com.dingtalk.api.request.OapiV2UserGetuserinfoRequest();
            req.setCode(authCode);
            com.dingtalk.api.response.OapiV2UserGetuserinfoResponse rsp = client.execute(req, accessToken);

            if (rsp.getErrcode() == 0) {
                String userid = rsp.getResult().getUserid();
                log.info("Successfully fetched DingTalk userid: {}", userid);
                return userid;
            } else {
                log.error("Failed to get userid from authCode: {}", rsp.getErrmsg());
                throw new RuntimeException("DingTalk SSO failed: " + rsp.getErrmsg());
            }
        } catch (Exception e) {
            log.error("Error in DingTalk SSO exchange", e);
            throw new RuntimeException("DingTalk SSO error", e);
        }
    }

    private String getAccessToken() throws ApiException {
        DingTalkClient client = new DefaultDingTalkClient("https://oapi.dingtalk.com/gettoken");
        OapiGettokenRequest req = new OapiGettokenRequest();
        req.setAppkey(dingTalkConfig.getAppKey());
        req.setAppsecret(dingTalkConfig.getAppSecret());
        req.setHttpMethod("GET");
        OapiGettokenResponse rsp = client.execute(req);
        if (rsp.getErrcode() == 0) {
            return rsp.getAccessToken();
        } else {
            throw new RuntimeException("Sync Token fetch failed: " + rsp.getErrmsg());
        }
    }

    private String getAccessTokenForApp() throws ApiException {
        DingTalkClient client = new DefaultDingTalkClient("https://oapi.dingtalk.com/gettoken");
        OapiGettokenRequest req = new OapiGettokenRequest();
        req.setAppkey(dingTalkAppConfig.getAppKey());
        req.setAppsecret(dingTalkAppConfig.getAppSecret());
        req.setHttpMethod("GET");
        OapiGettokenResponse rsp = client.execute(req);
        if (rsp.getErrcode() == 0) {
            return rsp.getAccessToken();
        } else {
            throw new RuntimeException("App Token fetch failed: " + rsp.getErrmsg());
        }
    }

    // Helper to fetch for single user to ensure data integrity
    private void fetchForSingleUser(String accessToken, String fromDateStr, String toDateStr, SysUser user) {
        try {
            DingTalkClient client = new DefaultDingTalkClient(
                    "https://oapi.dingtalk.com/topapi/attendance/getleavetimebynames");
            OapiAttendanceGetleavetimebynamesRequest req = new OapiAttendanceGetleavetimebynamesRequest();
            req.setUserid(user.getDingtalkUserId());
            req.setLeaveNames("年假");
            req.setFromDate(StringUtils.parseDateTime(fromDateStr));
            req.setToDate(StringUtils.parseDateTime(toDateStr));
            OapiAttendanceGetleavetimebynamesResponse rsp = client.execute(req, accessToken);

            if (rsp.getErrcode() != 0) {
                log.error("Failed to fetch for user {}: {}", user.getUsername(), rsp.getErrmsg());
                return;
            }

            processResponse(rsp.getBody(), user);
        } catch (Exception e) {
            log.error("Error fetching for user " + user.getUsername(), e);
        }
    }

    private void processResponse(String jsonBody, SysUser user) {
        if (jsonBody == null)
            return;
        JSONObject root = JSON.parseObject(jsonBody);
        JSONObject result = root.getJSONObject("result");
        if (result == null)
            return;
        JSONArray columns = result.getJSONArray("columns");
        if (columns == null)
            return;

        log.info("Processing {} columns for user {}", columns.size(), user.getUsername());

        for (int i = 0; i < columns.size(); i++) {
            JSONObject columnItem = columns.getJSONObject(i);
            JSONObject columnVo = columnItem.getJSONObject("columnvo");

            if (columnVo != null && "年假".equals(columnVo.getString("name"))) {
                JSONArray columnVals = columnItem.getJSONArray("columnvals");
                if (columnVals != null) {
                    log.info("Found {} daily entries for Annual Leave", columnVals.size());
                    for (int j = 0; j < columnVals.size(); j++) {
                        JSONObject dailyData = columnVals.getJSONObject(j);
                        String dateStr = dailyData.getString("date");
                        String valueStr = dailyData.getString("value");

                        log.info("Processing entry: date={}, value={}", dateStr, valueStr);

                        try {
                            double value = Double.parseDouble(valueStr);
                            if (value > 0) {
                                saveLeaveRecord(user, dateStr, value);
                            } else {
                                log.info("Skipping zero value entry");
                            }
                        } catch (Exception e) {
                            log.error("Error parsing value for date " + dateStr, e);
                        }
                    }
                }
            }
        }
    }

    private void saveLeaveRecord(SysUser user, String dateStr, double days) {
        LocalDate date;
        try {
            // Try parsing as DateTime first (e.g., "2025-12-02 00:00:00")
            date = LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toLocalDate();
        } catch (Exception e) {
            // Fallback to standard Date parsing (e.g., "2025-12-02")
            date = LocalDate.parse(dateStr);
        }

        BigDecimal daysBd = BigDecimal.valueOf(days);

        // Check if record exists to avoid duplicates
        Long count = leaveRecordMapper.selectCount(new QueryWrapper<LeaveRecord>()
                .eq("user_id", user.getId())
                .eq("start_date", date)
                .eq("type", "ANNUAL")
                .eq("days", daysBd.negate())); // Check for negative value (usage record)

        if (count == 0) {
            try {
                // ✅ 使用 applyLeave 方法，自动处理优先级和过期日期
                leaveService.applyLeave(user.getId(), date, date);
                log.info("✅ Applied leave via applyLeave for user {}: {} - {} days",
                        user.getUsername(), date, days);
            } catch (Exception e) {
                log.error("❌ Failed to apply leave for user {}: {}", user.getUsername(), e.getMessage(), e);
            }
        } else {
            log.info("⏭️ Skipping duplicate record for user {}: {} - {} days",
                    user.getUsername(), date, days);
        }
    }
}
