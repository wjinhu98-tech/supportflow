package dev.supportflow.shop.api;

import dev.supportflow.common.security.model.CustomerPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public shop labels and the buyer's legacy shop IDs; never exposes merchant/staff identities or order data. */
@RestController
public class ShopDirectoryController {
    private final JdbcClient jdbc;
    public ShopDirectoryController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/v1/shops")
    public List<ShopOption> list(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return jdbc.sql("""
                SELECT shop_id, name FROM (
                    SELECT shop.shop_id, shop.name
                    FROM support_merchant_shop shop JOIN support_merchant merchant ON merchant.id=shop.merchant_id
                    WHERE shop.status='ACTIVE' AND merchant.status='ACTIVE'
                    UNION ALL
                    SELECT legacy.tenant_id, CONCAT('店铺 ',legacy.tenant_id)
                    FROM (SELECT DISTINCT tenant_id FROM support_order WHERE buyer_id=:buyer LIMIT 50) legacy
                    WHERE NOT EXISTS (SELECT 1 FROM support_merchant_shop registered WHERE registered.shop_id=legacy.tenant_id)
                ) available ORDER BY shop_id LIMIT 50
                """).param("buyer", principal.getIdentity().buyerId())
                .query((row, ignored) -> new ShopOption(row.getLong("shop_id"), row.getString("name"))).list();
    }
    public record ShopOption(long shopId, String name) { }
}
