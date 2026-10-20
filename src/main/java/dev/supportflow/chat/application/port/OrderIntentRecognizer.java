package dev.supportflow.chat.application.port;

import dev.supportflow.chat.application.model.OrderIntent;

/** 把一条用户消息转换成待校验的查单意图，不接收认证身份或查询数据库。 */
@FunctionalInterface
public interface OrderIntentRecognizer {
    OrderIntent recognize(String message);
}
