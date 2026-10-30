package dev.supportflow.chat.support;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {BuyerHumanSupportController.class, MerchantHumanSupportController.class})
public class HumanSupportExceptionHandler {
    public record Error(HumanSupportException.Code code, String message) { }
    @ExceptionHandler(HumanSupportException.class)
    public ResponseEntity<Error> handle(HumanSupportException failure) {
        return ResponseEntity.status(failure.status()).body(new Error(failure.code(), failure.getMessage()));
    }
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Error> duplicate() {
        return handle(HumanSupportException.conflict("会话状态已变化，请刷新后重试"));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Error> storage() { return handle(HumanSupportException.unavailable()); }
}
