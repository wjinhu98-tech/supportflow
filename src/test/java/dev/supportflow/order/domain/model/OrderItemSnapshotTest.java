package dev.supportflow.order.domain.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderItemSnapshotTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");

    @Test
    void productImagesAreOptionalBoundedSameOriginStaticPaths() {
        assertThat(new OrderItemSnapshot("Headphones", null, 1).imageUrl()).isNull();
        assertThat(new OrderItemSnapshot("Headphones", null, 1, null).imageUrl()).isNull();
        for (String path : List.of("/products/demo/headphones-black.png", "/products/demo/cable_2.webp",
                "/products/demo/phone.jpg", "/products/demo/case.jpeg")) {
            assertThat(new OrderItemSnapshot("Product", null, 1, path).imageUrl()).isEqualTo(path);
        }
        String longest = "/products/demo/" + "a".repeat(221) + ".png";
        assertThat(longest).hasSize(240);
        assertThat(new OrderItemSnapshot("Product", null, 1, longest).imageUrl()).isEqualTo(longest);
        for (String path : List.of("", " ", "https://example.com/product.png", "//example.com/product.png",
                "data:image/png;base64,private", "javascript:private", "/products/demo/../private.png",
                "/products/demo/%2e%2e/private.png", "/products/demo/nested/private.png", "/products/demo/a.png?q=1",
                "/products/demo/a.png#fragment", "/products/demo/A.png", "/products/demo/a.PNG",
                "/products/demo/a.svg", "/products/other/a.png", "/products/demo/a.png\n",
                "/products/demo/a.png\r", " /products/demo/a.png", "/products/demo/a.png ",
                "/products/demo/" + "a".repeat(222) + ".png")) {
            assertThatThrownBy(() -> new OrderItemSnapshot("PRIVATE_PRODUCT", null, 1, path))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid purchase-time product image path");
        }
    }

    @Test
    void historicalItemJsonWithoutImageAndNewImageJsonAreBothReadable() throws Exception {
        var json = new ObjectMapper();
        assertThat(json.readValue("{\"productName\":\"Headphones\",\"quantity\":2}", OrderItemSnapshot.class))
                .isEqualTo(new OrderItemSnapshot("Headphones", null, 2));
        var item = new OrderItemSnapshot("Headphones", "Black", 2, "/products/demo/headphones.png");
        assertThat(json.readValue(json.writeValueAsString(item), OrderItemSnapshot.class)).isEqualTo(item);
    }

    @Test
    void purchasedNamesSpecificationsAndQuantityHaveExplicitBounds() {
        assertThat(new OrderItemSnapshot(" 蓝牙耳机 ", " 白色 ", 2))
                .isEqualTo(new OrderItemSnapshot("蓝牙耳机", "白色", 2));
        assertThat(new OrderItemSnapshot("耳机", " ", 1).specification()).isNull();
        assertThat(new OrderItemSnapshot("耳".repeat(100), "白".repeat(80), 9999).quantity()).isEqualTo(9999);
        for (String name : new String[]{null, "", " ", "耳".repeat(101), "耳机\n"}) {
            assertThatThrownBy(() -> new OrderItemSnapshot(name, null, 1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new OrderItemSnapshot("耳机", "白".repeat(81), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderItemSnapshot("耳机", "白色\n", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderItemSnapshot("耳机", null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderItemSnapshot("耳机", null, 10000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOrderOwnsAnImmutableBoundedSnapshotRatherThanTheCallersMutableList() {
        var source = new ArrayList<>(List.of(new OrderItemSnapshot("耳机", null, 1)));
        SupportOrder order = order(source);
        source.clear();
        assertThat(order.items()).hasSize(1);
        assertThatThrownBy(() -> order.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> order(Collections.nCopies(21, new OrderItemSnapshot("耳机", null, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void historicalConstructorsAndPersistedJsonKeepUnknownProductsEmpty() throws Exception {
        var old = new SupportOrder(1, 100, 200, 200, "SF10001", OrderStatus.PAID,
                new BigDecimal("99.00"), "CNY", NOW, NOW);
        var oldWithReceipt = new SupportOrder(1, 100, 200, 200, "SF10001", OrderStatus.COMPLETED,
                new BigDecimal("99.00"), "CNY", NOW, NOW, NOW);
        assertThat(old.items()).isEmpty();
        assertThat(oldWithReceipt.items()).isEmpty();
        String historical = """
                {"id":1,"tenantId":100,"customerId":200,"buyerId":200,"orderNo":"SF10001","status":"PAID",
                 "totalAmount":99,"currency":"CNY","createdAt":"2026-10-28T00:00:00Z","updatedAt":"2026-10-28T00:00:00Z"}
                """;
        assertThat(new ObjectMapper().readValue(historical, SupportOrder.class).items()).isEmpty();
    }

    private static SupportOrder order(List<OrderItemSnapshot> items) {
        return new SupportOrder(1, 100, 200, 200, "SF10001", OrderStatus.SHIPPED,
                new BigDecimal("99.00"), "CNY", NOW, NOW, null, items);
    }
}
