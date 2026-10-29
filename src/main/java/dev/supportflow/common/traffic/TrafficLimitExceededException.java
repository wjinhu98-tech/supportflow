package dev.supportflow.common.traffic;

/** Contains only fixed classification and retry duration; no identity or request data. */
public final class TrafficLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public TrafficLimitExceededException(long retryAfterSeconds) {
        super("请求较频繁，请稍后再试。");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() { return retryAfterSeconds; }
}
