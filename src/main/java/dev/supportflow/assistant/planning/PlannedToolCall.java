package dev.supportflow.assistant.planning;

import java.util.Objects;

/** Untrusted model parameters after structural validation, not authorization to execute a business action. */
public record PlannedToolCall(ToolName tool, String orderNo) {

    public PlannedToolCall {
        Objects.requireNonNull(tool, "tool must not be null");
        if (orderNo != null && (orderNo.isBlank() || orderNo.length() > 64
                || orderNo.codePoints().anyMatch(value -> Character.isWhitespace(value)
                || Character.isSpaceChar(value) || Character.isISOControl(value)))) {
            throw new IllegalArgumentException("orderNo must contain 1 to 64 non-whitespace characters");
        }
    }
}
