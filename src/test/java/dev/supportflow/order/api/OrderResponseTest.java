package dev.supportflow.order.api;

import dev.supportflow.order.api.response.OrderResponse;
import dev.supportflow.order.domain.model.OrderItemSnapshot;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderResponseTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");

    @Test
    void publicProductPreviewsExposeApprovedImagesAndHistoricalItemsKeepNullImages() throws Exception {
        var item = new OrderItemSnapshot("Headphones", "Black", 2, "/products/demo/headphones.png");
        var response = OrderResponse.from(new SupportOrder(901, 100, 999, 200, "SF10001", OrderStatus.SHIPPED,
                new BigDecimal("99.00"), "CNY", NOW, NOW, null, List.of(item)));
        var json = new ObjectMapper();
        String encoded = json.writeValueAsString(response);
        assertThat(json.readTree(encoded).get("items").get(0).get("imageUrl").asString())
                .isEqualTo("/products/demo/headphones.png");
        assertThat(json.readValue(encoded, OrderResponse.class)).isEqualTo(response);
        String historical = """
                {"shopId":100,"orderNo":"SF10001","status":"PAID","statusDescription":"Paid",
                 "totalAmount":99,"currency":"CNY","createdAt":"2026-10-28T00:00:00Z","updatedAt":"2026-10-28T00:00:00Z",
                 "items":[{"productName":"Headphones","quantity":2}],"itemKinds":1}
                """;
        assertThat(json.readValue(historical, OrderResponse.class).items())
                .containsExactly(new OrderItemSnapshot("Headphones", null, 2));
    }

    @Test
    void onlyThreePublicProductPreviewsAreReturnedWithTheActualLineCount() {
        var items = IntStream.range(0, 5).mapToObj(i -> new OrderItemSnapshot("商品" + i, "规格" + i, i + 1)).toList();
        var order = new SupportOrder(901, 100, 999, 200, "SF10001", OrderStatus.SHIPPED,
                new BigDecimal("99.00"), "CNY", NOW, NOW, null, items);
        var response = OrderResponse.from(order);
        assertThat(response.items()).containsExactlyElementsOf(items.subList(0, 3));
        assertThat(response.itemKinds()).isEqualTo(5);
        String encoded = new ObjectMapper().writeValueAsString(response);
        assertThat(encoded).doesNotContain("\"buyerId\"", "\"customerId\"", "\"tenantId\"", "\"id\"", "商品4");
    }

    @Test
    void historicResponsesWithoutProductPropertiesRemainReadable() {
        String historical = """
                {"shopId":100,"orderNo":"SF10001","status":"PAID","statusDescription":"待发货",
                "totalAmount":99,"currency":"CNY","createdAt":"2026-10-28T00:00:00Z","updatedAt":"2026-10-28T00:00:00Z"}
                """;
        var response = new ObjectMapper().readValue(historical, OrderResponse.class);
        assertThat(response.items()).isEmpty();
        assertThat(response.itemKinds()).isZero();
        assertThat(new OrderResponse(100, "SF10001", "PAID", "待发货", BigDecimal.ONE, "CNY", NOW, NOW, null).items()).isEmpty();
    }

    @Test
    void inconsistentPublicCountsCannotPretendThereAreUnrepresentedItems() {
        assertThatThrownBy(() -> new OrderResponse(100, "SF10001", "PAID", "待发货", BigDecimal.ONE, "CNY", NOW, NOW,
                null, List.of(new OrderItemSnapshot("耳机", null, 1)), 4)).isInstanceOf(IllegalArgumentException.class);
    }
}
