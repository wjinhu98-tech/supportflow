package dev.supportflow.chat.application;

/** 明确的查单指令缺少定位信息或格式不合法。 */
public final class InvalidChatCommandException extends RuntimeException {

    public InvalidChatCommandException() {
        super("查单格式：查订单 <商铺ID> <订单号>，例如：查订单 100 SF10001");
    }
}
