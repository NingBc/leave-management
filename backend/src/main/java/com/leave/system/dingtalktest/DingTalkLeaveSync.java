package com.leave.system.dingtalktest;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.dingtalk.api.DefaultDingTalkClient;
import com.dingtalk.api.DingTalkClient;
import com.dingtalk.api.request.OapiAttendanceGetleavetimebynamesRequest;
import com.dingtalk.api.request.OapiGettokenRequest;
import com.dingtalk.api.response.OapiAttendanceGetleavetimebynamesResponse;
import com.dingtalk.api.response.OapiGettokenResponse;
import com.taobao.api.ApiException;
import com.taobao.api.internal.util.StringUtils;

/**
 * 钉钉考勤数据同步工具 - 专门获取"年假"数据
 * 终极修复版：放弃 SDK 的 setter 方法，采用全手动参数注入，彻底解决 850015 缺少参数问题
 */
public class DingTalkLeaveSync {

    // --------------------------------------------------------------------------------
    // 关键配置说明：
    // 1. 在钉钉开发者后台 (open-dev.dingtalk.com) 创建一个 "H5微应用"。
    // 2. 在该应用的"权限管理"中，搜索并申请 "考勤" 权限 (如: 考勤打卡-查询考勤数据)。
    // 3. 将该应用的 AppKey 和 AppSecret 填入下方。
    // --------------------------------------------------------------------------------
    private static final String APP_KEY = "dingvtpnsylpixmtxrdo";
    private static final String APP_SECRET = "CvgoWAu6h1PPsK5JeaARrBv06Z9vNGlusLe-cUQnq-YxIUQ_flPfcUB_Mo220Pvh";

    public static void main(String[] args) {
        try {
            // 1. 获取调用凭证 AccessToken
            String accessToken = getAccessToken();
            System.out.println("✅ 获取Token成功: " + accessToken);

            // 2. 准备查询时间范围 (例如：查询 2023-10-01 到 2023-10-31)
            // 注意：不再需要 Date 对象，直接准备字符串
            String fromDateStr = "2025-12-01 00:00:00";
            String toDateStr = "2025-12-21 23:59:59";

            // 3. 准备员工ID列表 (注意：该接口一次最多支持 50 个用户)
            String userIds = "01525237116824149785";

            // 4. 执行核心逻辑
            fetchAnnualLeaveData(accessToken, fromDateStr, toDateStr, userIds);

        } catch (Exception e) {
            System.err.println("❌ 程序运行出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 获取 AccessToken
     */
    public static String getAccessToken() throws ApiException {
        DingTalkClient client = new DefaultDingTalkClient("https://oapi.dingtalk.com/gettoken");
        OapiGettokenRequest req = new OapiGettokenRequest();
        req.setAppkey(APP_KEY);
        req.setAppsecret(APP_SECRET);
        req.setHttpMethod("GET");
        OapiGettokenResponse rsp = client.execute(req);
        if (rsp.getErrcode() == 0) {
            return rsp.getAccessToken();
        } else {
            throw new RuntimeException("Token获取失败: " + rsp.getErrmsg());
        }
    }

    /**
     * 拉取考勤数据并筛选“年假”
     */
    public static void fetchAnnualLeaveData(String accessToken, String fromDateStr, String toDateStr, String userIds) throws ApiException {
        DingTalkClient client = new DefaultDingTalkClient("https://oapi.dingtalk.com/topapi/attendance/getleavetimebynames");
        OapiAttendanceGetleavetimebynamesRequest req = new OapiAttendanceGetleavetimebynamesRequest();
        req.setUserid(userIds);
        req.setLeaveNames("年假");
        req.setFromDate(StringUtils.parseDateTime(fromDateStr));
        req.setToDate(StringUtils.parseDateTime(toDateStr));
        OapiAttendanceGetleavetimebynamesResponse rsp = client.execute(req, accessToken);
        System.out.println(rsp.getBody());

        if (rsp.getErrcode() != 0) {
            System.err.println("❌ 钉钉接口调用失败: " + rsp.getErrmsg());
            // 打印出完整的 body 以便调试
            System.err.println("完整响应: " + rsp.getBody());
            return;
        }

        // --- 核心修改：放弃使用 SDK 的 getResult()，直接解析 JSON 字符串 ---
        // 这样可以 100% 避免 Cannot resolve method 错误
        String jsonBody = rsp.getBody();
        if (jsonBody == null || jsonBody.isEmpty()) {
            System.out.println("❌ 接口返回体为空");
            return;
        }

        JSONObject root = JSON.parseObject(jsonBody);

        // 获取 result 对象
        JSONObject result = root.getJSONObject("result");
        if (result == null) {
            System.out.println("⚠️ 未找到 result 数据 (可能没有权限或没有数据)");
            return;
        }

        // 获取 columns 列表 (这里是第一层：用户列表)
        JSONArray userList = result.getJSONArray("columns");
        if (userList == null || userList.isEmpty()) {
            System.out.println("⚠️ 指定范围内没有查到任何请假记录");
            return;
        }

        System.out.println("--- 开始处理年假数据 ---");

        // 3. 遍历 columns (通常这里包含不同类型的假期，或者不同人的数据)
        for (int i = 0; i < userList.size(); i++) {
            JSONObject columnItem = userList.getJSONObject(i);

            // 4. 检查是否是"年假" (查看 columnvo 字段)
            JSONObject columnVo = columnItem.getJSONObject("columnvo");
            if (columnVo != null && "年假".equals(columnVo.getString("name"))) {

                // 5. 获取每日详情 (columnvals)
                JSONArray columnVals = columnItem.getJSONArray("columnvals");
                if (columnVals != null) {

                    // 6. 遍历每日数据
                    for (int j = 0; j < columnVals.size(); j++) {
                        JSONObject dailyData = columnVals.getJSONObject(j);

                        String date = dailyData.getString("date");
                        String valueStr = dailyData.getString("value");

                        // 7. 核心过滤：排除 0.0 的数据
                        // 建议使用 Double 解析比较，防止出现 "0" 或 "0.00" 字符串不匹配的情况
                        try {
                            double value = Double.parseDouble(valueStr);

                            // 只要大于 0 就提取
                            if (value > 0) {
                                System.out.println("📅 日期: " + date + "  -->  ⏳ 请假时长: " + valueStr);

                                // TODO: 在这里将 date 和 value 存入你的数据库
                                // saveToDb(userId, date, value);
                            }
                        } catch (NumberFormatException e) {
                            // 忽略非数字的异常
                        }
                    }
                }
            }
        }

        System.out.println("--- 处理结束 ---");
    }
}