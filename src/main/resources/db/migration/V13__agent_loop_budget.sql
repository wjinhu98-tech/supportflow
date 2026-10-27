-- Preserve V12 runs as workflow 1. Only newly created runs use the observation-driven loop.
ALTER TABLE support_agent_run
    ADD COLUMN workflow_version INT NOT NULL DEFAULT 1,
    ADD COLUMN loop_state_json JSON NULL,
    ADD COLUMN budget_json JSON NULL,
    ADD CONSTRAINT ck_agent_run_workflow CHECK (
        (workflow_version=1 AND loop_state_json IS NULL AND budget_json IS NULL)
        OR (workflow_version=2 AND loop_state_json IS NOT NULL AND budget_json IS NOT NULL)),
    DROP CHECK ck_agent_run_failure,
    ADD CONSTRAINT ck_agent_run_failure CHECK (failure_code IS NULL OR failure_code IN
        ('EXECUTION_INTERRUPTED', 'MODEL_UNAVAILABLE', 'TOOL_UNAVAILABLE', 'VALIDATION_REJECTED',
         'DEADLINE_EXCEEDED', 'STEP_LIMIT_REACHED', 'MODEL_BUDGET_EXCEEDED'));

ALTER TABLE support_agent_step
    ADD COLUMN metering_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN reserved_tokens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN reported_tokens BIGINT NULL,
    ADD COLUMN charged_cost_usd DECIMAL(20,12) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_agent_step_metering CHECK (metering_status IN ('NONE', 'RESERVED', 'SETTLED')),
    ADD CONSTRAINT ck_agent_step_tokens CHECK (reserved_tokens BETWEEN 0 AND 33792
        AND (reported_tokens IS NULL OR reported_tokens BETWEEN 0 AND reserved_tokens)),
    ADD CONSTRAINT ck_agent_step_cost CHECK (charged_cost_usd BETWEEN 0 AND 1000);
