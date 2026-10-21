package dev.supportflow.assistant.execution;

import java.math.BigDecimal;
import java.time.LocalDateTime;

record StoredExecution(String requestId, String claimToken, long orderId, long buyerId, long shopId,
                       BigDecimal amount, String currency, ExecutionStatus status,
                       int attemptCount, int maxAttempts, LocalDateTime leaseUntil, String lastErrorCode) {
    ExecutionClaim claim(String token, int attempts) {
        return new ExecutionClaim(requestId, token, orderId, buyerId, shopId, amount, currency, attempts, maxAttempts);
    }
}
