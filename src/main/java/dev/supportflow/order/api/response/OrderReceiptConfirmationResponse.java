package dev.supportflow.order.api.response;

import dev.supportflow.assistant.aftersale.AfterSaleEligibility;

/** Public receipt confirmation; no internal order, buyer or audit identifiers are exposed. */
public record OrderReceiptConfirmationResponse(
        OrderResponse order, boolean alreadyConfirmed, AfterSaleEligibility eligibility
) { }
