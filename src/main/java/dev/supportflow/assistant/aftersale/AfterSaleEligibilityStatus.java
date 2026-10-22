package dev.supportflow.assistant.aftersale;

/** Stable customer-facing policy outcomes, distinct from a case's review status. */
public enum AfterSaleEligibilityStatus {
    ELIGIBLE,
    REQUIRES_REVIEW,
    EXPIRED,
    INELIGIBLE
}
