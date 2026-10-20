package dev.supportflow.chat.application;

/** 对外只暴露固定提示，不携带供应商响应、API Key或请求原文。 */
public final class ModelUnavailableException extends RuntimeException {

    public ModelUnavailableException() {
        super("智能查单暂时不可用，请稍后重试或使用固定查单指令");
    }
}
