package dev.supportflow.order.domain;

/**
 * 订单查询共用的分页边界，供接口、应用服务和仓储使用。
 */
public final class OrderQueryLimits {

    public static final int MAX_ORDER_NO_LENGTH = 64;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    /** 额外读取一行，仅用于判断是否存在下一页，不返回给客户端。 */
    public static final int LOOKAHEAD_ROWS = 1;

    public static final int MAX_FETCH_SIZE = MAX_PAGE_SIZE + LOOKAHEAD_ROWS;

    private OrderQueryLimits() {
    }
}
