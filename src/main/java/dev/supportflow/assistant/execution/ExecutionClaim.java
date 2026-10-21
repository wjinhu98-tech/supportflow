package dev.supportflow.assistant.execution;

import java.math.BigDecimal;

/** Server-created work token. Never accepted as an HTTP request body. */
public record ExecutionClaim(String requestId, String claimToken, long orderId, long buyerId,
                             long shopId, BigDecimal amount, String currency,
                             int attemptCount, int maxAttempts) {
}
