-- Extend purchase-time product snapshots without changing any existing row or inventing images.
-- The pre-existing table SELECT grant also covers imageUrl inside this JSON column.
-- Keep replacement atomic: there is no interval with the product constraint removed.
ALTER TABLE support_order
    DROP CHECK ck_support_order_product_items,
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
                  "quantity":{"type":"integer","minimum":1,"maximum":9999},
                  "imageUrl":{
                    "anyOf":[
                      {"type":"null"},
                      {
                        "type":"string",
                        "maxLength":240,
                        "pattern":"^/products/demo/[a-z0-9_-]+[.](png|webp|jpg|jpeg)$",
                        "not":{"pattern":"[^/a-z0-9_.-]"}
                      }
                    ]
                  }
                }
              }
            }', product_items_json)
    );
