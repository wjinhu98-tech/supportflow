package dev.supportflow.common.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/** Fixed stage names and safe outcomes only: payloads and business/account identifiers cannot be supplied. */
public final class StageTrace implements AutoCloseable {
    public enum Stage { HTTP_REQUEST, AGENT_PLAN, ORDER_TOOL, LOGISTICS_TOOL }
    private static final Logger LOG = LoggerFactory.getLogger(StageTrace.class);
    private final Stage stage;
    private final long started = System.nanoTime();
    private boolean success;

    private StageTrace(Stage stage) { this.stage = java.util.Objects.requireNonNull(stage); }

    public static StageTrace start(Stage stage) { return new StageTrace(stage); }
    public void succeeded() { success = true; }

    @Override public void close() {
        LOG.info("request_stage stage={} outcome={} durationMs={} traceId={} agentRunId={}",
                stage, success ? "SUCCESS" : "FAILED", TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - started)),
                TraceContext.traceId(), TraceContext.agentRunId());
    }
}
