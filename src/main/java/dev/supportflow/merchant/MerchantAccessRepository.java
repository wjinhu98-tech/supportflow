package dev.supportflow.merchant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MerchantAccessRepository {
    private static final String ACTIVE_ACCESS = """
            FROM support_merchant_staff staff
            JOIN support_merchant merchant ON merchant.id=staff.merchant_id
            JOIN support_merchant_staff_shop_grant access_grant ON access_grant.staff_id=staff.id
            JOIN support_merchant_shop shop ON shop.shop_id=access_grant.shop_id
              AND shop.merchant_id=merchant.id
            WHERE staff.username=:username AND staff.linked_buyer_id=:buyer
              AND staff.status='ACTIVE' AND merchant.status='ACTIVE' AND shop.status='ACTIVE'
              AND access_grant.enabled=TRUE
            """;
    private final JdbcClient jdbc;

    public MerchantAccessRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    boolean activeStaff(String username, long buyerId) {
        return jdbc.sql("""
                SELECT staff.id FROM support_merchant_staff staff
                JOIN support_merchant merchant ON merchant.id=staff.merchant_id
                WHERE staff.username=:username AND staff.linked_buyer_id=:buyer
                  AND staff.status='ACTIVE' AND merchant.status='ACTIVE'
                """).param("username", username).param("buyer", buyerId)
                .query(Long.class).optional().isPresent();
    }

    List<MerchantShop> list(String username, long buyerId) {
        return jdbc.sql("SELECT shop.shop_id, shop.name, merchant.id AS merchant_id, "
                        + "merchant.name AS merchant_name " + ACTIVE_ACCESS + " ORDER BY shop.shop_id")
                .param("username", username).param("buyer", buyerId)
                .query((row, ignored) -> new MerchantShop(row.getLong("shop_id"), row.getString("name"),
                        row.getLong("merchant_id"), row.getString("merchant_name"))).list();
    }

    Optional<MerchantShopAccess> find(String username, long buyerId, long shopId, boolean lock) {
        // In a write transaction the current shared read locks every participating authorization row.
        // Revocation and the approval therefore have a clear commit order, with no authorization cache.
        return jdbc.sql("SELECT staff.id AS staff_id, shop.shop_id " + ACTIVE_ACCESS
                        + " AND shop.shop_id=:shop" + (lock ? " FOR SHARE" : ""))
                .param("username", username).param("buyer", buyerId).param("shop", shopId)
                .query((row, ignored) -> new MerchantShopAccess(row.getLong("staff_id"), row.getLong("shop_id")))
                .optional();
    }
}
