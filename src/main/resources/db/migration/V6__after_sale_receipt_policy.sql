-- Trusted receipt timestamps must be supplied by the order system; do not invent historical dates.
ALTER TABLE support_order
    ADD COLUMN received_at DATETIME(3) NULL COMMENT '确认收货时间，UTC；历史未知值保持NULL';

-- An accepted application's submission-time policy decision is immutable during later review/execution.
ALTER TABLE support_after_sale
    ADD COLUMN eligibility_status VARCHAR(32) NULL COMMENT '提交时售后资格；历史申请保持NULL',
    ADD COLUMN eligibility_can_submit BOOLEAN NULL COMMENT '提交时是否允许申请',
    ADD COLUMN eligibility_message VARCHAR(500) NULL COMMENT '提交时资格说明',
    ADD COLUMN eligibility_received_at DATETIME(3) NULL COMMENT '提交时确认收货时间，UTC',
    ADD COLUMN eligibility_deadline_at DATETIME(3) NULL COMMENT '提交时申请截止时间，UTC',
    ADD COLUMN eligibility_checked_at DATETIME(3) NULL COMMENT '提交时资格检查时间，UTC',
    ADD COLUMN eligibility_window_days SMALLINT NULL COMMENT '提交时适用的售后天数',
    ADD CONSTRAINT ck_after_sale_eligibility_snapshot CHECK (
        (eligibility_status IS NULL
            AND eligibility_can_submit IS NULL AND eligibility_message IS NULL
            AND eligibility_received_at IS NULL AND eligibility_deadline_at IS NULL
            AND eligibility_checked_at IS NULL AND eligibility_window_days IS NULL)
        OR
        (eligibility_status IS NOT NULL AND eligibility_status IN ('ELIGIBLE', 'REQUIRES_REVIEW')
            AND eligibility_can_submit IS NOT NULL AND eligibility_can_submit = TRUE
            AND eligibility_message IS NOT NULL AND CHAR_LENGTH(TRIM(eligibility_message)) > 0
            AND eligibility_checked_at IS NOT NULL
            AND eligibility_window_days IS NOT NULL AND eligibility_window_days BETWEEN 1 AND 365
            AND ((eligibility_status = 'ELIGIBLE'
                AND eligibility_received_at IS NOT NULL AND eligibility_deadline_at IS NOT NULL
                AND eligibility_received_at <= eligibility_checked_at
                AND eligibility_checked_at < eligibility_deadline_at)
                OR eligibility_status = 'REQUIRES_REVIEW'))
    );
