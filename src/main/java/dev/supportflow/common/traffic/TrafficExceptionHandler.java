package dev.supportflow.common.traffic;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrafficExceptionHandler {
    @ExceptionHandler(TrafficLimitExceededException.class)
    public ResponseEntity<Map<String, String>> limited(TrafficLimitExceededException failure) {
        return ResponseEntity.status(429).header("Retry-After", Long.toString(failure.retryAfterSeconds()))
                .body(Map.of("code", "RATE_LIMITED", "message", "请求较频繁，请稍后再试。"));
    }
}
