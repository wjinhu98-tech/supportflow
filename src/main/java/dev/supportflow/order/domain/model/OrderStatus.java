package dev.supportflow.order.domain.model;

/**
 * 订单状态。
 *
 * <p>code 用于持久化及对外状态编码，description 用于展示。</p>
 */
public enum OrderStatus {

    CREATED("CREATED", "待支付"),
    PAID("PAID", "待发货"),
    SHIPPED("SHIPPED", "已发货"),
    COMPLETED("COMPLETED", "已完成"),
    CANCELLED("CANCELLED", "已取消");

    private final String code;
    private final String description;

    OrderStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 将持久化状态编码转换为业务枚举。
     *
     * @param code 状态编码
     * @return 对应的订单状态
     * @throws IllegalArgumentException 编码为空或不受支持
     */
    public static OrderStatus fromCode(String code) {
        for (OrderStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }

        throw new IllegalArgumentException(
                "Unsupported order status code: " + code
        );
    }
}