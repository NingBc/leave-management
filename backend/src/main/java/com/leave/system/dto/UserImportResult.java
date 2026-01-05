package com.leave.system.dto;

import lombok.Data;

/**
 * 用户导入结果DTO
 */
@Data
public class UserImportResult {
    private int totalCount; // 总行数
    private int successCount; // 成功数
    private int failureCount; // 失败数
    private java.util.List<String> errors; // 错误详情

    public UserImportResult() {
        this.errors = new java.util.ArrayList<>();
    }

    public void addError(int lineNumber, String message) {
        this.errors.add(String.format("第%d行: %s", lineNumber, message));
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public java.util.List<String> getErrors() {
        return errors;
    }

    public void setErrors(java.util.List<String> errors) {
        this.errors = errors;
    }
}
