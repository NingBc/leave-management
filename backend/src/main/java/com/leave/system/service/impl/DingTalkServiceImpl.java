package com.leave.system.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONObject;

import com.dingtalk.api.DefaultDingTalkClient;
import com.dingtalk.api.request.*;
import com.dingtalk.api.response.*;
import com.leave.system.entity.LeaveRecord;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.LeaveRecordMapper;
import com.leave.system.mapper.SysUserMapper;
import com.leave.system.service.DingTalkService;
import com.leave.system.config.DingTalkConfig;
import com.leave.system.config.DingTalkAppConfig;
import com.leave.system.service.LeaveService;
import com.leave.system.exception.BusinessException;
import com.taobao.api.ApiException;
import com.taobao.api.internal.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Collections;
import java.util.Set;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

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
    private LeaveService leaveService;

    @Autowired
    @Lazy
    private DingTalkServiceImpl self;

    // NEW: Serialized Sync Queue for multiple users
    private final LinkedBlockingQueue<Long> syncQueue = new LinkedBlockingQueue<>();
    private final Set<Long> pendingSyncUsers = Collections.synchronizedSet(new java.util.HashSet<>());
    private ExecutorService syncExecutor;

    @PostConstruct
    public void init() {
        syncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DingTalk-Sync-Worker");
            t.setDaemon(true);
            return t;
        });
        syncExecutor.submit(this::processSyncQueue);
        log.info("DingTalk Sync Worker started.");
    }

    @PreDestroy
    public void shutdown() {
        if (syncExecutor != null) {
            syncExecutor.shutdownNow();
        }
    }

    private void processSyncQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Long userId = syncQueue.take();
                pendingSyncUsers.remove(userId);

                log.info("Processing queued sync for user ID: {}", userId);
                self.doSyncToDingTalk(userId);

                // Controlled delay: 500ms between any two sync operations to avoid burst limits
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in DingTalk sync worker", e);
            }
        }
    }

    @Override
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

            // Sequential sync with a small delay to respect DingTalk QPS limits (max 20/s)
            for (SysUser user : users) {
                log.info("Fetching data for user: {} (DingTalk ID: {})", user.getUsername(), user.getDingtalkUserId());
                fetchForSingleUser(accessToken, fromDateStr, toDateStr, user);

                try {
                    // 50ms interval ensures we don't exceed 20 QPS (1000ms / 50ms = 20)
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
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
            DefaultDingTalkClient client = new DefaultDingTalkClient(
                    "https://oapi.dingtalk.com/topapi/v2/user/getuserinfo");
            client.setConnectTimeout(10000); // 10s
            client.setReadTimeout(15000); // 15s
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
        DefaultDingTalkClient client = new DefaultDingTalkClient("https://oapi.dingtalk.com/gettoken");
        client.setConnectTimeout(5000);
        client.setReadTimeout(10000);
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
        DefaultDingTalkClient client = new DefaultDingTalkClient("https://oapi.dingtalk.com/gettoken");
        client.setConnectTimeout(5000);
        client.setReadTimeout(10000);
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
            DefaultDingTalkClient client = new DefaultDingTalkClient(
                    "https://oapi.dingtalk.com/topapi/attendance/getleavetimebynames");
            client.setConnectTimeout(10000);
            client.setReadTimeout(20000); // Larger timeout for complex attendance query
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

            String body = rsp.getBody();
            if (body == null || body.trim().isEmpty()) {
                log.warn("Empty response body for user: {}", user.getUsername());
                return;
            }

            processResponse(body, user);
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
                    log.info("Found {} daily entries for Custom Annual Leave", columnVals.size());
                    for (int j = 0; j < columnVals.size(); j++) {
                        JSONObject dailyData = columnVals.getJSONObject(j);
                        String dateStr = dailyData.getString("date");
                        String valueStr = dailyData.getString("value");

                        log.info("Processing entry: date={}, value={}", dateStr, valueStr);

                        try {
                            double value = Double.parseDouble(valueStr);
                            if (value > 0) {
                                self.saveLeaveRecord(user, dateStr, value);
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

    @Override
    public void syncToDingTalk() {
        log.info("Starting Full Sync to DingTalk...");
        List<SysUser> users = userMapper.selectUsersWithDingtalkId();
        for (SysUser user : users) {
            try {
                self.syncToDingTalk(user.getId());
                Thread.sleep(100); // 10 QPS
            } catch (Exception e) {
                log.error("Failed to sync user {} to DingTalk: {}", user.getUsername(), e.getMessage());
            }
        }
        log.info("Full Sync to DingTalk completed.");
    }

    @Override
    public void syncToDingTalk(Long userId) {
        if (pendingSyncUsers.add(userId)) {
            if (syncQueue.offer(userId)) {
                log.info("Sync request for user {} added to queue. (Queue size: {})", userId, syncQueue.size());
            } else {
                pendingSyncUsers.remove(userId);
                log.error("Failed to add sync request for user {} to queue (queue full).", userId);
            }
        } else {
            log.info("Sync request for user {} is already pending in queue, skipping duplicate.", userId);
        }
    }

    // Actual sync logic moved from syncToDingTalk(userId) to
    // doSyncToDingTalk(userId)
    @Transactional(rollbackFor = Exception.class)
    public void doSyncToDingTalk(Long userId) {
        SysUser user = userMapper.selectUserById(userId);
        if (user == null || StringUtils.isEmpty(user.getDingtalkUserId())
                || user.getDingtalkUserId().trim().isEmpty()) {
            log.warn("User {} has no DingTalk ID, skipping sync.", userId);
            return;
        }

        String leaveCode = dingTalkConfig.getAnnualLeaveCode();
        if (StringUtils.isEmpty(leaveCode)) {
            log.warn("DingTalk annualLeaveCode is not configured, skipping sync.");
            return;
        }

        log.info("Executing serialized sync for user {} to DingTalk (Code: {})", user.getRealName(), leaveCode);

        try {
            String accessToken = getAccessToken();
            int currentYear = LocalDate.now().getYear();
            String opUserId = getOpUserId();

            // 2. Get Local Balance and Account
            com.leave.system.entity.LeaveAccount account = leaveService.getAccount(userId, currentYear);
            BigDecimal localBalance = account.getTotalBalance();
            log.info("Local balance for user {}: {} days", user.getRealName(), localBalance);

            // 3. Balance Change Check
            if (account.getLastSyncedBalance() != null && account.getLastSyncedBalance().compareTo(localBalance) == 0) {
                log.info("Skipping sync for user {}: Balance has not changed since last sync ({})",
                        user.getRealName(), localBalance);
                return;
            }

            // Execute actual API call
            DefaultDingTalkClient updateClient = new DefaultDingTalkClient(
                    "https://oapi.dingtalk.com/topapi/attendance/vacation/quota/init");
            OapiAttendanceVacationQuotaInitRequest initReq = new OapiAttendanceVacationQuotaInitRequest();
            initReq.setOpUserid(opUserId);

            OapiAttendanceVacationQuotaInitRequest.LeaveQuotas quota = new OapiAttendanceVacationQuotaInitRequest.LeaveQuotas();
            quota.setUserid(user.getDingtalkUserId());
            quota.setLeaveCode(leaveCode);
            quota.setQuotaNumPerDay(localBalance.multiply(new BigDecimal(100)).longValue());
            quota.setReason("Synchronized from Leave Management System (Init)");
            quota.setQuotaCycle(String.valueOf(currentYear));
            quota.setQuotaNumPerHour(0L);

            long startTimeMillis = LocalDate.of(currentYear, 1, 1).atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
            long endTimeMillis = LocalDate.of(currentYear + 1, 12, 31).atTime(23, 59, 59)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();

            quota.setStartTime(startTimeMillis);
            quota.setEndTime(endTimeMillis);

            List<OapiAttendanceVacationQuotaInitRequest.LeaveQuotas> quotas = new ArrayList<>();
            quotas.add(quota);
            initReq.setLeaveQuotas(quotas);

            log.info("Sending DingTalk INIT Request for user {}: {}", user.getRealName(),
                    JSON.toJSONString(initReq.getTextParams()));

            OapiAttendanceVacationQuotaInitResponse updateRsp = updateClient.execute(initReq, accessToken);
            if (updateRsp.getErrcode() == 0) {
                log.info("Successfully initialized DingTalk balance for user {}.", user.getRealName());
                // Update last synced info
                account.setLastSyncedBalance(localBalance);
                leaveService.updateAccount(account);
            } else {
                log.error("Failed to update DingTalk balance for user {}: {}", user.getRealName(),
                        updateRsp.getErrmsg());
            }

        } catch (Exception e) {
            log.error("Error syncing user {} to DingTalk", user.getRealName(), e);
        }
    }

    @Override
    public String listVacationTypes() {
        log.info("Listing DingTalk vacation types for discovery...");
        try {
            String accessToken = getAccessToken();
            String opUserId = getOpUserId();
            DefaultDingTalkClient client = new DefaultDingTalkClient(
                    "https://oapi.dingtalk.com/topapi/attendance/vacation/type/list");
            OapiAttendanceVacationTypeListRequest req = new OapiAttendanceVacationTypeListRequest();
            req.setOpUserid(opUserId);
            OapiAttendanceVacationTypeListResponse rsp = client.execute(req, accessToken);

            if (rsp.getErrcode() == 0) {
                return JSON.toJSONString(rsp.getResult());
            } else {
                log.error("Failed to list vacation types: {}", rsp.getErrmsg());
                return "Error: " + rsp.getErrmsg();
            }
        } catch (Exception e) {
            log.error("Error listing vacation types", e);
            return "Error: " + e.getMessage();
        }
    }

    private String getOpUserId() {
        if (!StringUtils.isEmpty(dingTalkConfig.getOpUserid())) {
            return dingTalkConfig.getOpUserid();
        }
        throw new BusinessException(
                "No valid DingTalk user found in system to use as opUserid. Please configure dingtalk.op-userid in application.yml.");
    }

    @Transactional(rollbackFor = Exception.class)
    protected void saveLeaveRecord(SysUser user, String dateStr, double days) {
        LocalDate date;
        try {
            // Try parsing as DateTime first (e.g., "2025-12-02 00:00:00")
            date = LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toLocalDate();
        } catch (Exception e) {
            // Fallback to standard Date parsing (e.g., "2025-12-02")
            date = LocalDate.parse(dateStr);
        }

        BigDecimal reportDays = BigDecimal.valueOf(days);

        // Delta Sync Logic: Compare total daily duration to handle fragmented or
        // duplicate-looking records
        // 1. Get sum of existing annual leave records for this user on this day (stored
        // as negative)
        BigDecimal localSum = leaveRecordMapper.sumAnnualLeaveUsage(user.getId(), date);
        BigDecimal localSumAbs = localSum.abs();

        // 2. Calculate the difference (Delta)
        // e.g., DingTalk Report = 1.0, Local = 0.5 -> diff = 0.5 (need to apply more)
        // e.g., DingTalk Report = 1.0, Local = 1.0 -> diff = 0 (perfect match, skip)
        BigDecimal diff = reportDays.subtract(localSumAbs);

        if (diff.compareTo(BigDecimal.ZERO) > 0) {
            log.info("📊 Delta detected for user {} on {}: Report={}, Local={}, Applying diff={}",
                    user.getUsername(), date, reportDays, localSumAbs, diff);
            try {
                // Apply the difference via priority deduction logic (automatically sets
                // expiry_date)
                leaveService.applyLeave(user.getId(), date, date, diff);
                log.info("✅ Applied delta for user {}: {} - {} days",
                        user.getUsername(), date, diff);
            } catch (Exception e) {
                log.error("❌ Failed to apply delta for user {}: {}", user.getUsername(), e.getMessage(), e);
            }
        } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal toReturn = diff.abs();
            log.info("⏪ Reduction detected for user {} on {}: Report={}, Local={}, Refunding diff={}",
                    user.getUsername(), date, reportDays, localSumAbs, toReturn);

            List<LeaveRecord> records = leaveRecordMapper.selectAnnualRecordsByDate(user.getId(), date);
            for (LeaveRecord record : records) {
                // Scheme A: Only refund records that appear to be auto-synced (based on remark
                // pattern)
                if (record.getRemarks() == null || !record.getRemarks().contains("(来自")) {
                    log.info("⏭️ Skipping refund for protected/manual record: {}", record.getRemarks());
                    continue;
                }

                BigDecimal recordAbs = record.getDays().abs();
                if (recordAbs.compareTo(toReturn) <= 0) {
                    // Fully refund this record (Soft Delete)
                    record.setDeleted(1);
                    record.setRemarks(record.getRemarks() + " (钉钉同步撤销)");
                    leaveRecordMapper.updateRecord(record);
                    toReturn = toReturn.subtract(recordAbs);
                } else {
                    // Partially refund this record
                    record.setDays(record.getDays().add(toReturn));
                    record.setRemarks(record.getRemarks() + " (钉钉同步部分撤销)");
                    leaveRecordMapper.updateRecord(record);
                    toReturn = BigDecimal.ZERO;
                }

                if (toReturn.compareTo(BigDecimal.ZERO) <= 0)
                    break;
            }

            if (toReturn.compareTo(BigDecimal.ZERO) > 0) {
                log.warn("⚠️ Could not fully refund {} days for user {} on {}. Remaining: {}",
                        diff.abs(), user.getUsername(), date, toReturn);
            }
        } else {
            log.info("⏭️ Data consistent for user {} on {}: {} days", user.getUsername(), date, reportDays);
        }
    }
}
