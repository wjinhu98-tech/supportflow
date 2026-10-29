import dev.supportflow.deployment.LegacyDemoProductData;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit original learning fixtures only; no startup import, migration or real purchase inference. */
public final class LocalDemoProductDetails {
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/supportflow_rebuild"
            + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=5000&socketTimeout=15000";
    private static final String TARGETS = """
            ((tenant_id=100 AND order_no IN ('SFDEMO2026102101','SFDEMO2026102102','SFDEMO2026102103',
              'SFDEMO2026102104','SFDEMO2026102105','SFDEMO2026102106','SFDEMO2026102107','SFDEMO2026102108'))
             OR (tenant_id=101 AND order_no IN ('SFDEMO2026102103','SFDEMO2026102109')))
            """;

    public static void main(String[] args) {
        try {
            if (args.length != 1 || !List.of("inspect", "apply", "verify").contains(args[0]))
                throw new IllegalArgumentException("Expected inspect, apply or verify");
            boolean apply = args[0].equals("apply");
            String key = apply ? "SUPPORTFLOW_MIGRATOR_PASSWORD" : "SUPPORTFLOW_APP_PASSWORD";
            String password = System.getenv(key);
            if (password == null || password.isBlank()) {
                Map<?, ?> secrets = new Yaml(new SafeConstructor(new LoaderOptions()))
                        .load(Files.readString(Path.of(".local/secrets.yml")));
                if (!(secrets.get(key) instanceof String value) || value.isBlank()) throw new IllegalStateException();
                password = value;
            }
            try (Connection connection = DriverManager.getConnection(URL,
                    apply ? "supportflow_migrator" : "supportflow_app", password)) {
                verifyDatabase(connection);
                if (apply) {
                    connection.setAutoCommit(false);
                    try {
                        var before = metadata(connection);
                        var grants = grants(connection);
                        var result = LegacyDemoProductData.seed(connection);
                        if (!before.equals(metadata(connection)) || !grants.equals(grants(connection)))
                            throw new IllegalStateException("Unrelated learning data changed");
                        connection.commit();
                        System.out.println("LOCAL_LEGACY_PRODUCTS_APPLIED inserted=" + result.inserted()
                                + " preserved=" + result.preserved() + " orderMetadataChanged=0 staffGrantsChanged=0");
                    } catch (Exception failure) {
                        connection.rollback();
                        throw failure;
                    }
                }
                report(connection);
            }
        } catch (Exception failure) {
            // Never print parser/driver details or nested causes from private credentials.
            System.err.println("LOCAL_LEGACY_PRODUCTS_FAILED type=" + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void verifyDatabase(Connection connection) throws Exception {
        try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT DATABASE()")) {
            if (!rows.next() || !"supportflow_rebuild".equals(rows.getString(1))) throw new IllegalStateException();
        }
        try (var query = connection.createStatement(); var rows = query.executeQuery("""
                SELECT SUM(version='17' AND success=1),SUM(success=0) FROM flyway_schema_history
                """)) {
            if (!rows.next() || rows.getInt(1) != 1 || rows.getInt(2) != 0) throw new IllegalStateException();
        }
    }

    private static List<List<String>> metadata(Connection connection) throws Exception {
        var result = new ArrayList<List<String>>();
        try (var query = connection.createStatement(); var rows = query.executeQuery("""
                SELECT id,tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency,
                       created_at,updated_at,received_at
                FROM support_order WHERE
                """ + TARGETS + " ORDER BY tenant_id,order_no")) {
            while (rows.next()) {
                var row = new ArrayList<String>();
                for (int column = 1; column <= 11; column++) row.add(rows.getString(column));
                result.add(row);
            }
        }
        if (result.size() != 10) throw new IllegalStateException("Original demo fixtures required");
        return result;
    }

    private static List<String> grants(Connection connection) throws Exception {
        var result = new ArrayList<String>();
        try (var query = connection.createStatement(); var rows = query.executeQuery("""
                SELECT staff_id,shop_id,enabled FROM support_merchant_staff_shop_grant ORDER BY staff_id,shop_id
                """)) {
            while (rows.next()) result.add(rows.getLong(1) + ":" + rows.getLong(2) + ":" + rows.getBoolean(3));
        }
        return result;
    }

    private static void report(Connection connection) throws Exception {
        try (var query = connection.createStatement(); var rows = query.executeQuery("""
                SELECT tenant_id,COUNT(*),SUM(JSON_LENGTH(product_items_json)>0),
                       SUM(JSON_EXTRACT(product_items_json,'$[0].imageUrl') IS NOT NULL)
                FROM support_order WHERE buyer_id=200 AND
                """ + TARGETS + " GROUP BY tenant_id ORDER BY tenant_id")) {
            while (rows.next()) System.out.println("LOCAL_LEGACY_PRODUCTS shop=" + rows.getLong(1)
                    + " orders=" + rows.getInt(2) + " withProducts=" + rows.getInt(3) + " withImages=" + rows.getInt(4));
        }
    }
}
