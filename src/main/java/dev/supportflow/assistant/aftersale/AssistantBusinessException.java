package dev.supportflow.assistant.aftersale;

import dev.supportflow.common.api.error.ErrorCode;
import org.springframework.http.HttpStatus;

public final class AssistantBusinessException extends RuntimeException {
    private final ErrorCode code;
    private final HttpStatus status;

    public AssistantBusinessException(ErrorCode code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public ErrorCode code() { return code; }
    public HttpStatus status() { return status; }
}
