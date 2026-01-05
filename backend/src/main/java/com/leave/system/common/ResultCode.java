package com.leave.system.common;

/**
 * 响应状态码枚举
 */
public class ResultCode {

    // 成功
    public static final int SUCCESS = 200;

    // 客户端错误
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;

    // 服务器错误
    public static final int INTERNAL_ERROR = 500;

    private ResultCode() {
        // 工具类，私有化构造函数
    }
}
