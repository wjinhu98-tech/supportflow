package dev.supportflow.order.infrastructure.persistence.jdbc;

import dev.supportflow.order.domain.model.OrderItemSnapshot;
import org.springframework.dao.DataRetrievalFailureException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Shared strict decoder for normal reads and receipt locks; the JSON payload is never placed in an error. */
final class OrderItemSnapshotJson {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> FIELDS = Set.of("productName", "specification", "quantity", "imageUrl");

    private OrderItemSnapshotJson() { }

    static List<OrderItemSnapshot> read(String value) {
        if (value == null) return List.of();
        try (var parser = JSON.createParser(value)) {
            JsonNode rows = JSON.readTree(parser);
            if (parser.nextToken() != null || rows == null || !rows.isArray() || rows.size() > OrderItemSnapshot.MAX_ITEMS)
                throw new IllegalArgumentException("Invalid product snapshot array");
            var items = new ArrayList<OrderItemSnapshot>();
            for (JsonNode row : rows) {
                if (!row.isObject() || !FIELDS.containsAll(row.propertyNames()))
                    throw new IllegalArgumentException("Invalid product snapshot item");
                JsonNode name = row.get("productName"), specification = row.get("specification"), quantity = row.get("quantity"),
                        imageUrl = row.get("imageUrl");
                if (name == null || !name.isString() || quantity == null || !quantity.isIntegralNumber() || !quantity.canConvertToInt()
                        || specification != null && !specification.isNull() && !specification.isString()
                        || imageUrl != null && !imageUrl.isNull() && !imageUrl.isString())
                    throw new IllegalArgumentException("Invalid product snapshot field type");
                items.add(new OrderItemSnapshot(name.asString(), specification == null || specification.isNull()
                        ? null : specification.asString(), quantity.intValue(),
                        imageUrl == null || imageUrl.isNull() ? null : imageUrl.asString()));
            }
            return List.copyOf(items);
        } catch (RuntimeException invalid) {
            throw new DataRetrievalFailureException("Invalid persisted order product snapshots");
        }
    }
}
