package dev.supportflow.order.application.exception;

import java.io.Serial;

/**
 * 当前身份对应的查询范围内没有可访问的订单。
 */
public final class OrderNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public OrderNotFoundException() {
        super("订单不存在或无权访问");
    }
}