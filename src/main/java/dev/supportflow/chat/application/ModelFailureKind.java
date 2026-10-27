package dev.supportflow.chat.application;

/** Safe classifications only; never contains a provider message, payload, URL or credential. */
public enum ModelFailureKind {
    UNCLASSIFIED, BUSY, TIMEOUT, TRANSPORT, PROVIDER_TEMPORARY, RATE_LIMITED,
    PROVIDER_REJECTED, INVALID_RESPONSE, INTERRUPTED, CANCELLED, CIRCUIT_OPEN
}
