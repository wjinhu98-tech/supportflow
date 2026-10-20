package dev.supportflow.chat.application;

/** 明确的订单指令缺少信息或格式不合法。提示由程序定义，不回显用户原文。 */
public final class InvalidChatCommandException extends RuntimeException {

    public InvalidChatCommandException() {
        this("查单格式：查订单 <订单号>，商铺由当前咨询页面确定");
    }

    public InvalidChatCommandException(String message) {
        super(message);
    }
}
