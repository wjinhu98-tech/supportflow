package dev.supportflow.chat.infrastructure.deepseek;

/** Fixed, low-cardinality labels; never derive operations from user input. */
public enum DeepSeekCallOperation {
    ORDER_INTENT,
    TOOL_PLAN
}
