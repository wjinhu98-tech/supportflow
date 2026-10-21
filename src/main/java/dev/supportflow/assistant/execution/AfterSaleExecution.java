package dev.supportflow.assistant.execution;

import java.math.BigDecimal;
import java.time.Instant;

/** Public progress view; excludes lease tokens and internal order/buyer IDs. */
public record AfterSaleExecution(ExecutionStatus status, BigDecimal amount, String currency,
                                 int attemptCount, int maxAttempts, Instant nextAttemptAt,
                                 Instant completedAt, String reference, String lastError,
                                 boolean retryable) {
}
