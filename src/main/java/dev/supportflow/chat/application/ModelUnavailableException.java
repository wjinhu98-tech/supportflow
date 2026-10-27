package dev.supportflow.chat.application;

/** 对外只暴露固定提示，不携带供应商响应、API Key或请求原文。 */
public final class ModelUnavailableException extends RuntimeException {

    private final ModelFailureKind kind;

    public ModelUnavailableException() {
        this(ModelFailureKind.UNCLASSIFIED);
    }

    public ModelUnavailableException(ModelFailureKind kind) {
        super("智能服务暂时不可用，请稍后重试，或在订单页直接查看订单。");
        this.kind = java.util.Objects.requireNonNull(kind);
    }

    public ModelFailureKind kind() { return kind; }
}
