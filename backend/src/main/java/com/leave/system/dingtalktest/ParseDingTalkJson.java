package com.leave.system.dingtalktest;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ParseDingTalkJson {

    public static void main(String[] args) {
        // 模拟你接口返回的原始 JSON 字符串
        String jsonBody = "{\"errcode\":0,\"errmsg\":\"ok\",\"result\":{\"columns\":[{\"columnvals\":[{\"date\":\"2025-12-01 00:00:00\",\"value\":\"0.0\"},{\"date\":\"2025-12-02 00:00:00\",\"value\":\"1.0\"},{\"date\":\"2025-12-03 00:00:00\",\"value\":\"1.0\"},{\"date\":\"2025-12-04 00:00:00\",\"value\":\"0.5\"},{\"date\":\"2025-12-05 00:00:00\",\"value\":\"0.0\"},{\"date\":\"2025-12-08 00:00:00\",\"value\":\"1.0\"}],\"columnvo\":{\"alias\":\"leave_\",\"name\":\"年假\",\"status\":0,\"sub_type\":0,\"type\":0}}],\"request_id\":\"15rl142jdl90t\"}}";

        parseAndPrintLeaveData(jsonBody);
    }

    public static void parseAndPrintLeaveData(String jsonBody) {
        if (jsonBody == null || jsonBody.isEmpty()) {
            return;
        }

        JSONObject root = JSON.parseObject(jsonBody);
        
        // 1. 获取 result
        JSONObject result = root.getJSONObject("result");
        if (result == null) return;

        // 2. 获取 columns 列表
        JSONArray columns = result.getJSONArray("columns");
        if (columns == null) return;

        System.out.println("--- 开始解析年假数据 ---");

        // 3. 遍历 columns (通常这里包含不同类型的假期，或者不同人的数据)
        for (int i = 0; i < columns.size(); i++) {
            JSONObject columnItem = columns.getJSONObject(i);

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
        System.out.println("--- 解析结束 ---");
    }
}