package dev.supportflow.assistant.planning;

import java.util.Objects;
import java.util.Set;

/** Offline learning mode: extracts explicit SF order numbers and never invents missing identifiers. */
public final class RuleBasedToolPlanner implements ToolPlanner {
    @Override
    public PlannedToolCall plan(ToolName permittedTool, String message, String selectedOrderNo) {
        Objects.requireNonNull(permittedTool, "permittedTool must not be null");
        Objects.requireNonNull(message, "message must not be null");
        new PlannedToolCall(permittedTool, selectedOrderNo);
        Set<String> explicit = ExplicitOrderNumbers.extract(message);
        if (explicit.size() > 1) {
            return new PlannedToolCall(permittedTool, null);
        }
        String orderNo = explicit.isEmpty() ? selectedOrderNo : explicit.iterator().next();
        // A too-long user identifier must not be truncated into a different existing order.
        return new PlannedToolCall(permittedTool, orderNo != null && orderNo.length() > 64 ? null : orderNo);
    }
}
