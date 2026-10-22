package dev.supportflow.assistant.aftersale;

import java.time.Instant;

/** A server-evaluated preview, or the immutable eligibility snapshot recorded on submission. */
public record AfterSaleEligibility(
        long shopId, String orderNo, AfterSaleEligibilityStatus status, boolean canSubmit,
        String message, Instant receivedAt, Instant deadlineAt, Instant checkedAt, int windowDays
) { }
