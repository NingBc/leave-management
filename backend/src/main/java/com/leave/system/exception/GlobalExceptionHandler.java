package com.leave.system.exception;

import com.leave.system.common.Result;
import com.leave.system.common.ResultCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLSyntaxErrorException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        log.warn("Business Exception: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal Argument: {}", e.getMessage());
        return Result.error(ResultCode.BAD_REQUEST, e.getMessage());
    }

    /**
     * 处理权限不足异常。
     *
     * <p>
     * 必须显式声明, 否则会被下面的 Exception 兜底处理器吞成 500。
     * 同时要回真正的 HTTP 403 —— 本项目其他错误走「HTTP 200 + body.code」的约定,
     * 但鉴权失败若也返回 200, 监控、网关、非浏览器客户端都无法识别。
     * 前端 request.js 的错误拦截器本来就处理 403 ("权限不足: ...")。
     */
    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(AccessDeniedException.class)
    public Result<?> handleAccessDenied(AccessDeniedException e) {
        log.warn("Access Denied: {}", e.getMessage());
        return Result.error(ResultCode.FORBIDDEN, "无权访问该资源");
    }

    /**
     * 处理SQL语法错误
     */
    @ExceptionHandler(SQLSyntaxErrorException.class)
    public Result<?> handleSQLSyntaxError(SQLSyntaxErrorException e) {
        log.error("SQL Syntax Error: {}", e.getMessage(), e);
        return Result.error(ResultCode.INTERNAL_ERROR, "数据库错误: " + e.getMessage());
    }

    /**
     * 处理所有未捕获的异常
     */
    @ExceptionHandler(Exception.class)
    public Result<?> handleGenericException(Exception e) {
        log.error("Unhandled Exception: {}", e.getMessage(), e);

        String message = "系统内部错误";
        // 如果是开发环境，可以在这里添加更多详情，但生产环境建议隐藏
        if (e.getMessage() != null) {
            if (e.getMessage().contains("SQLSyntaxErrorException")) {
                message = "数据库错误，请检查日志";
            } else {
                message = e.getMessage();
            }
        }
        return Result.error(ResultCode.INTERNAL_ERROR, message);
    }
}
