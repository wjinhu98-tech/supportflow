package dev.supportflow.chat.application;

/** 明确的订单指令缺少信息或格式不合法。提示由程序定义，不回显用户原文。 */
public final class InvalidChatCommandException extends RuntimeException {

    public InvalidChatCommandException() {
        this("查单格式：查订单 <商铺ID> <订单号>，例如：查订单 100 SF10001");
    }

    public InvalidChatCommandException(String message) {
        super(message);
    }
}
