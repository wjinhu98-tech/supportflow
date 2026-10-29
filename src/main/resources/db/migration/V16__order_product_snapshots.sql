-- Purchase-time product data for the read-only customer-service order projection.
-- Existing orders remain NULL: never invent products from order numbers or current catalogue data.
-- SELECT privileges already granted on support_order cover the new column; no new table or write grant.
-- MySQL 8.0 supports inline JSON_SCHEMA_VALID in CHECK constraints:
-- https://dev.mysql.com/doc/refman/8.0/en/json-validation-functions.html
ALTER TABLE support_order
    ADD COLUMN product_items_json JSON NULL COMMENT '购买时商品快照；NULL表示历史商品信息未知',
    ADD CONSTRAINT ck_support_order_product_items CHECK (
        product_items_json IS NULL OR JSON_SCHEMA_VALID(
            '{
              "type":"array",
              "maxItems":20,
              "items":{
                "type":"object",
                "additionalProperties":false,
                "required":["productName","quantity"],
                "properties":{
                  "productName":{"type":"string","minLength":1,"maxLength":100},
                  "specification":{"type":["string","null"],"maxLength":80},
                  "quantity":{"type":"integer","minimum":1,"maximum":9999}
                }
              }
            }', product_items_json)
    );
