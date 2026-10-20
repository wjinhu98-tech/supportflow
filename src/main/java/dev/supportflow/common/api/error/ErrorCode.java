package dev.supportflow.common.api.error;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 接口错误分类及默认提示。
 */
public enum ErrorCode {

    INVALID_ARGUMENT("INVALID_ARGUMENT", "请求参数不合法"),
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", "订单不存在或无权访问"),
    AUTHENTICATION_REQUIRED("AUTHENTICATION_REQUIRED", "请先完成身份认证"),
    ACCESS_DENIED("ACCESS_DENIED", "无权执行此操作"),
    MODEL_UNAVAILABLE("MODEL_UNAVAILABLE", "智能查单暂时不可用，请稍后重试或使用固定查单指令"),
    REQUEST_REJECTED("REQUEST_REJECTED", "请求无法处理"),
    INTERNAL_ERROR("INTERNAL_ERROR", "服务暂时不可用，请稍后重试");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
