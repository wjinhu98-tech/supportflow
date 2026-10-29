package dev.supportflow.order.domain.model;

/** Purchase-time customer-visible product data; it does not describe a live catalogue or per-item shipment. */
public record OrderItemSnapshot(String productName, String specification, int quantity, String imageUrl) {
    public static final int MAX_ITEMS = 20;
    private static final java.util.regex.Pattern DEMO_IMAGE_PATH =
            java.util.regex.Pattern.compile("/products/demo/[a-z0-9_-]+[.](png|webp|jpg|jpeg)");

    /** Snapshots written before image support keep their unknown image empty. */
    public OrderItemSnapshot(String productName, String specification, int quantity) {
        this(productName, specification, quantity, null);
    }

    public OrderItemSnapshot {
        if (productName == null || productName.isBlank() || productName.length() > 100
                || productName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid purchase-time product name");
        }
        productName = productName.strip();
        if (specification != null) {
            if (specification.length() > 80 || specification.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid purchase-time product specification");
            specification = specification.isBlank() ? null : specification.strip();
        }
        if (quantity < 1 || quantity > 9999) throw new IllegalArgumentException("Invalid purchased quantity");
        if (imageUrl != null && (imageUrl.length() > 240 || !DEMO_IMAGE_PATH.matcher(imageUrl).matches()))
            throw new IllegalArgumentException("Invalid purchase-time product image path");
    }
}
