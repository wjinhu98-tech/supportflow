-- Old V1/V2 checkpoints keep automatic retries disabled; only new V2 runs snapshot the new policy.
ALTER TABLE support_agent_run
    ADD COLUMN auto_retry_limit INT NOT NULL DEFAULT 0,
    ADD COLUMN retry_delay_ms INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_agent_run_retry CHECK (auto_retry_limit BETWEEN 0 AND 1
        AND retry_delay_ms BETWEEN 0 AND 1000 AND (auto_retry_limit=1 OR retry_delay_ms=0));

ALTER TABLE support_agent_step
    ADD COLUMN failure_kind VARCHAR(32) NULL,
    ADD COLUMN retry_scheduled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_agent_step_failure CHECK (failure_kind IS NULL OR failure_kind IN
        ('MODEL_TIMEOUT', 'MODEL_TRANSPORT', 'MODEL_TEMPORARY', 'TOOL_TRANSIENT', 'MODEL_UNAVAILABLE',
         'MODEL_BUSY', 'MODEL_RATE_LIMITED', 'MODEL_REJECTED', 'MODEL_INVALID_RESPONSE', 'MODEL_CIRCUIT_OPEN',
         'MODEL_CANCELLED', 'EXECUTION_INTERRUPTED', 'TOOL_UNAVAILABLE', 'VALIDATION_REJECTED')),
    ADD CONSTRAINT ck_agent_step_retry CHECK (retry_scheduled=FALSE OR
        (status='FAILED' AND failure_kind IS NOT NULL AND
         failure_kind IN ('MODEL_TIMEOUT', 'MODEL_TRANSPORT', 'MODEL_TEMPORARY', 'TOOL_TRANSIENT')));
