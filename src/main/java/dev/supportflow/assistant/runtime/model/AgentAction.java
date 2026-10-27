package dev.supportflow.assistant.runtime.model;

/** Every model-visible capability is read-only; no reflection or business writes. */
public enum AgentAction {
    ORDER_LOOKUP("get_order"), LOGISTICS_LOOKUP("get_logistics"), ANSWER("finish"), CLARIFY("clarify");
    private final String code;
    AgentAction(String code) { this.code = code; }
    public String code() { return code; }
    public static AgentAction fromCode(String code) {
        for (AgentAction action : values()) if (action.code.equals(code)) return action;
        throw new IllegalArgumentException("Unsupported read-only action");
    }
}
