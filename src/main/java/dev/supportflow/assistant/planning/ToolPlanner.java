package dev.supportflow.assistant.planning;

/** One bounded planning step. The server router, not the model, chooses the permitted business capability. */
public interface ToolPlanner {
    PlannedToolCall plan(ToolName permittedTool, String message, String selectedOrderNo);
}
