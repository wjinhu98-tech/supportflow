package dev.supportflow.chat.support;

import org.springframework.http.HttpStatus;

public final class HumanSupportException extends RuntimeException {
    public enum Code { HUMAN_SUPPORT_NOT_FOUND, HUMAN_SUPPORT_CONFLICT, HUMAN_SUPPORT_INVALID, HUMAN_SUPPORT_UNAVAILABLE }
    private final Code code;
    private final HttpStatus status;
    private HumanSupportException(Code code, HttpStatus status, String message) {
        super(message); this.code = code; this.status = status;
    }
    public Code code() { return code; }
    public HttpStatus status() { return status; }
    public static HumanSupportException notFound() {
        return new HumanSupportException(Code.HUMAN_SUPPORT_NOT_FOUND, HttpStatus.NOT_FOUND, "人工会话不存在或无权访问");
    }
    public static HumanSupportException conflict(String message) {
        return new HumanSupportException(Code.HUMAN_SUPPORT_CONFLICT, HttpStatus.CONFLICT, message);
    }
    public static HumanSupportException invalid() {
        return new HumanSupportException(Code.HUMAN_SUPPORT_INVALID, HttpStatus.BAD_REQUEST, "请填写有效的纯文本消息，最多2000字");
    }
    public static HumanSupportException unavailable() {
        return new HumanSupportException(Code.HUMAN_SUPPORT_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "人工会话暂时不可用，请刷新后重试");
    }
}
