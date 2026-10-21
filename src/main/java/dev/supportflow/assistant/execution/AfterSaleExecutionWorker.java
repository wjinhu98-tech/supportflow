package dev.supportflow.assistant.execution;

import org.springframework.stereotype.Service;

/** The worker has no end-user credentials and executes only database-approved tasks. */
@Service
public class AfterSaleExecutionWorker {
    private final AfterSaleExecutionProcessor processor;

    public AfterSaleExecutionWorker(AfterSaleExecutionProcessor processor) { this.processor = processor; }

    public boolean runOnce() {
        var claimed = processor.claimNext();
        if (claimed.isEmpty()) return false;
        ExecutionClaim claim = claimed.get();
        try {
            processor.complete(claim);
        } catch (ExecutionRejectedException rejected) {
            processor.recordFailure(claim, ExecutionFailureCode.BUSINESS_RULE_VIOLATION);
        } catch (RuntimeException failure) {
            processor.recordFailure(claim, ExecutionFailureCode.TRANSIENT_FAILURE);
        }
        return true;
    }
}
