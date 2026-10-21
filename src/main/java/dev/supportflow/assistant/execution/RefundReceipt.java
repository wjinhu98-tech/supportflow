package dev.supportflow.assistant.execution;

import java.math.BigDecimal;
import java.time.Instant;

public record RefundReceipt(String reference, BigDecimal amount, String currency, Instant completedAt) {
}
