package dev.supportflow.assistant.execution;

/** Stable categories; raw infrastructure exceptions never become customer-facing text. */
public enum ExecutionFailureCode {
    TRANSIENT_FAILURE, BUSINESS_RULE_VIOLATION, ATTEMPTS_EXHAUSTED
}
