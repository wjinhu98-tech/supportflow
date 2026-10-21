package dev.supportflow.assistant.planning;

/** Server-owned tool allowlist; names are never resolved through reflection. */
public enum ToolName {
    ORDER_LOOKUP("get_order"),
    LOGISTICS_LOOKUP("get_logistics"),
    AFTER_SALE_REQUEST("request_after_sale");

    private final String code;

    ToolName(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
