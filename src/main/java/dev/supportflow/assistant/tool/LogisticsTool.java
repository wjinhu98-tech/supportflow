package dev.supportflow.assistant.tool;

import dev.supportflow.order.domain.model.SupportOrder;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Reads logistics events recorded inside this project for an already-authorized
 * order. The orchestrator must first resolve the order through OrderQueryService;
 * a model-provided internal order ID must never be passed directly to this tool.
 */
@Service
public class LogisticsTool {

    private static final int MAX_EVENTS = 50;
    private static final String FIND_EVENTS = """
            SELECT occurred_at, description
            FROM support_shipment_event
            WHERE order_id = :orderId
            ORDER BY occurred_at ASC, id ASC
            LIMIT :limit
            """;

    private final JdbcClient jdbcClient;

    public LogisticsTool(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public String lookup(SupportOrder order) {
        Objects.requireNonNull(order, "validated order must not be null");
        if (order.id() <= 0) {
            throw new IllegalArgumentException("validated order ID must be positive");
        }
        List<ShipmentEvent> events = jdbcClient.sql(FIND_EVENTS)
                .param("orderId", order.id())
                .param("limit", MAX_EVENTS)
                .query(LogisticsTool::mapRow)
                .list();
        String heading = "订单 " + order.orderNo() + " 的物流记录：";
        if (events.isEmpty()) {
            return heading + "本项目物流模块暂无录入的物流事件。"
                    + "订单状态不等于物流轨迹，本功能未连接真实快递查询服务。";
        }
        StringBuilder reply = new StringBuilder(heading)
                .append("\n以下事件来自本项目物流模块，时间为 UTC；并非实时快递联网查询。\n");
        for (ShipmentEvent event : events) {
            reply.append(event.occurredAt()).append(" · ")
                    .append(event.description()).append('\n');
        }
        if (events.size() == MAX_EVENTS) {
            reply.append("当前最多展示 ").append(MAX_EVENTS).append(" 条已录入事件。\n");
        }
        return reply.toString().stripTrailing();
    }

    private static ShipmentEvent mapRow(ResultSet row, int rowNumber) throws SQLException {
        LocalDateTime occurredAt = row.getObject("occurred_at", LocalDateTime.class);
        String description = row.getString("description");
        if (occurredAt == null || description == null || description.isBlank()) {
            throw new DataRetrievalFailureException("Invalid persisted shipment event");
        }
        return new ShipmentEvent(occurredAt.toInstant(ZoneOffset.UTC), description);
    }

    private record ShipmentEvent(Instant occurredAt, String description) {
    }
}
