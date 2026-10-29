package dev.supportflow.order.infrastructure.persistence.jdbc;

import dev.supportflow.order.domain.model.OrderItemSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderItemSnapshotJsonTest {
    @Test
    void missingAndNullImageFieldsStayUnknownAndApprovedPathsAreKept() {
        assertThat(OrderItemSnapshotJson.read("[{\"productName\":\"Headphones\",\"quantity\":1}]"))
                .containsExactly(new OrderItemSnapshot("Headphones", null, 1));
        assertThat(OrderItemSnapshotJson.read("[{\"productName\":\"Headphones\",\"quantity\":1,\"imageUrl\":null}]"))
                .containsExactly(new OrderItemSnapshot("Headphones", null, 1));
        assertThat(OrderItemSnapshotJson.read("[{\"productName\":\"Headphones\",\"quantity\":1,\"imageUrl\":\"/products/demo/headphones.png\"}]"))
                .containsExactly(new OrderItemSnapshot("Headphones", null, 1, "/products/demo/headphones.png"));
    }

    @Test
    void imageTypesAndUnapprovedPathsFailWithoutLeakingThePayload() {
        for (String imageValue : new String[]{"123", "true", "{}", "[]", "\"\"",
                "\"https://private.example/product.png\"", "\"//private.example/product.png\"",
                "\"data:image/png;base64,PRIVATE_IMAGE\"", "\"/products/demo/../private.png\"",
                "\"/products/demo/a.png?PRIVATE_IMAGE\"", "\"/products/demo/a.png#PRIVATE_IMAGE\"",
                "\"/products/demo/A.png\"", "\"/products/demo/a.svg\"", "\"/products/demo/a.png\\n\"",
                "\"/products/demo/" + "a".repeat(222) + ".png\""}) {
            String persisted = "[{\"productName\":\"PRIVATE_PRODUCT\",\"quantity\":1,\"imageUrl\":" + imageValue + "}]";
            assertThatThrownBy(() -> OrderItemSnapshotJson.read(persisted))
                    .isInstanceOf(DataRetrievalFailureException.class)
                    .hasMessage("Invalid persisted order product snapshots").hasNoCause();
        }
    }

    @Test
    void nullRemainsUnknownWhileValidSnapshotsRoundTripWithoutInventedValues() {
        assertThat(OrderItemSnapshotJson.read(null)).isEmpty();
        assertThat(OrderItemSnapshotJson.read("[]")).isEmpty();
        assertThat(OrderItemSnapshotJson.read("[{\"productName\":\"耳机\",\"specification\":\"白色\",\"quantity\":2}]"))
                .containsExactly(new OrderItemSnapshot("耳机", "白色", 2));
    }

    @Test
    void malformedOrCoercedDataFailsWithoutPuttingItsPayloadInTheException() {
        for (String value : new String[]{"null", "{}", "[null]", "[{\"productName\":\"PRIVATE_PRODUCT\",\"quantity\":1.5}]",
                "[{\"productName\":123,\"quantity\":1}]", "[{\"productName\":\"PRIVATE_PRODUCT\",\"quantity\":\"2\"}]",
                "[{\"productName\":\"PRIVATE_PRODUCT\",\"quantity\":0}]", "[] []",
                "[{\"productName\":\"PRIVATE_PRODUCT\",\"quantity\":1,\"extra\":true}]",
                "[{\"productName\":\"PRIVATE_PRODUCT\",\"quantity\":true}]",
                "[{\"productName\":\"PRIVATE_PRODUCT\",\"specification\":1,\"quantity\":2}]"}) {
            assertThatThrownBy(() -> OrderItemSnapshotJson.read(value)).isInstanceOf(DataRetrievalFailureException.class)
                    .hasMessage("Invalid persisted order product snapshots").hasNoCause();
        }
    }
}
