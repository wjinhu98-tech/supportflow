package dev.supportflow.chat.conversation;

import org.springframework.http.HttpStatus;

public final class ConversationException extends RuntimeException {
    public enum Code { CONVERSATION_NOT_FOUND, CONVERSATION_CONFLICT, CONVERSATION_UNAVAILABLE }

    private final Code code;
    private final HttpStatus status;

    private ConversationException(Code code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public Code code() { return code; }
    public HttpStatus status() { return status; }

    public static ConversationException notFound() {
        return new ConversationException(Code.CONVERSATION_NOT_FOUND, HttpStatus.NOT_FOUND,
                "会话或历史记录不存在，或你无权查看");
    }

    public static ConversationException conflict(String message) {
        return new ConversationException(Code.CONVERSATION_CONFLICT, HttpStatus.CONFLICT, message);
    }

    public static ConversationException unavailable() {
        return new ConversationException(Code.CONVERSATION_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                "本次回复未能完整保存，请先刷新记录并核实处理结果，不要重复提交退款等操作");
    }
}
