package dev.supportflow.common.api.error;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 接口错误分类及默认提示。
 */
public enum ErrorCode {

    ORDER_RECEIPT_CONFLICT("ORDER_RECEIPT_CONFLICT", "当前订单状态不支持确认收货，请刷新后重试；历史订单缺少收货时间时，请联系客服核实。"),

    INVALID_ARGUMENT("INVALID_ARGUMENT", "请求参数不合法"),
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", "订单不存在或无权访问"),
    AUTHENTICATION_REQUIRED("AUTHENTICATION_REQUIRED", "请先完成身份认证"),
    ACCESS_DENIED("ACCESS_DENIED", "无权执行此操作"),
    MODEL_UNAVAILABLE("MODEL_UNAVAILABLE", "智能查单暂时不可用，请稍后重试或使用固定查单指令"),
    REQUEST_REJECTED("REQUEST_REJECTED", "请求无法处理"),
    REVIEW_NOT_FOUND("REVIEW_NOT_FOUND", "售后申请不存在"),
    REVIEW_CONFLICT("REVIEW_CONFLICT", "售后申请已处理或状态已变化"),
    KNOWLEDGE_NOT_FOUND("KNOWLEDGE_NOT_FOUND", "知识资料不存在"),
    KNOWLEDGE_CONFLICT("KNOWLEDGE_CONFLICT", "知识资料已更新或状态已变化，请刷新后重试"),
    KNOWLEDGE_LIMIT_REACHED("KNOWLEDGE_LIMIT_REACHED", "店铺已达到同时发布资料的数量上限"),
    BUSINESS_RULE_VIOLATION("BUSINESS_RULE_VIOLATION", "不符合当前业务处理条件"),
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
