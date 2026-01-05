package com.leave.system.exception;

import com.leave.system.common.ResultCode;
import lombok.Getter;

/**
 * 业务异常类
 * 用于在业务逻辑中抛出预期内的错误，由全局异常处理器统一捕获
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.BAD_REQUEST; // 默认为客户端请求错误
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
