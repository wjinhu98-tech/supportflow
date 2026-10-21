package dev.supportflow.assistant.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "supportflow.after-sale.execution.enabled", havingValue = "true")
public class AfterSaleExecutionScheduling {
    private static final Logger LOG = LoggerFactory.getLogger(AfterSaleExecutionScheduling.class);
    private static final int BATCH_SIZE = 10;
    private final AfterSaleExecutionWorker worker;

    public AfterSaleExecutionScheduling(AfterSaleExecutionWorker worker) { this.worker = worker; }

    @Scheduled(fixedDelayString = "${supportflow.after-sale.execution.poll-delay-ms:2000}", initialDelay = 3000)
    public void poll() {
        try {
            for (int index = 0; index < BATCH_SIZE && worker.runOnce(); index++) { /* bounded batch */ }
        } catch (RuntimeException failure) {
            // A failed status/audit transaction leaves the lease for the next poll to recover.
            LOG.warn("After-sale task polling failed ({}); durable tasks remain available for recovery",
                    failure.getClass().getSimpleName());
        }
    }
}
