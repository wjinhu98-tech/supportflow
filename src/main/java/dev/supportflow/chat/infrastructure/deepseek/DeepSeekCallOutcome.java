package dev.supportflow.chat.infrastructure.deepseek;

/** Internal diagnostics only. The public API retains its existing safe unavailable response. */
enum DeepSeekCallOutcome {
    SUCCESS,
    BUSY,
    TIMEOUT,
    INTERRUPTED,
    PROVIDER_RATE_LIMITED,
    PROVIDER_ERROR,
    INVALID_RESPONSE,
    TRANSPORT_ERROR,
    CANCELLED,
    CIRCUIT_OPEN
}
