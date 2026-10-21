package dev.supportflow.assistant.execution;

public class ExecutionRejectedException extends RuntimeException {
    public ExecutionRejectedException(String message) { super(message); }
}
