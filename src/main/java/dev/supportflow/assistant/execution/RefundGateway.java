package dev.supportflow.assistant.execution;

/** Processes server-created work and returns an idempotent refund receipt. */
public interface RefundGateway {
    RefundReceipt refund(ExecutionClaim claim);
}
