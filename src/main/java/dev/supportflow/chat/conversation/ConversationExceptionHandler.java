package dev.supportflow.chat.conversation;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ConversationController.class, AgentRunController.class})
public class ConversationExceptionHandler {
    public record ConversationError(ConversationException.Code code, String message) { }

    @ExceptionHandler(ConversationException.class)
    public ResponseEntity<ConversationError> handle(ConversationException failure) {
        return ResponseEntity.status(failure.status()).body(new ConversationError(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ConversationError> handleStorageUnavailable() {
        return handle(ConversationException.unavailable());
    }
}
