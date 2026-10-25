import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local deployment diagnostics. Reads existing credentials privately, never grants or edits accounts. */
public final class LocalKnowledgeSetup {
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/supportflow_rebuild"
            + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=5000&socketTimeout=15000";

    public static void main(String[] args) {
        if (args.length != 1 || !Set.of("validate", "inspect").contains(args[0])) {
            throw new IllegalArgumentException("Usage: LocalKnowledgeSetup.java validate|inspect");
        }
        try {
            var loader = new YamlPropertiesFactoryBean();
            loader.setResources(new FileSystemResource(".local/secrets.yml"));
            var secrets = loader.getObject();
            if (secrets == null) throw new IllegalStateException("Local credentials missing");
            if (!args[0].equals("inspect")) {
                var flyway = Flyway.configure().dataSource(URL, "supportflow_migrator",
                                secrets.getProperty("SUPPORTFLOW_MIGRATOR_PASSWORD"))
                        .locations("filesystem:src/main/resources/db/migration")
                        .validateOnMigrate(true).baselineOnMigrate(false).cleanDisabled(true).createSchemas(false)
                        .load();
                var validation = flyway.validateWithResult();
                System.out.println("MIGRATION_CHECKSUM_VALID=" + validation.validationSuccessful);
                for (var invalid : validation.invalidMigrations) {
                    System.out.println("INVALID_MIGRATION=" + invalid.version + " CODE=" + invalid.errorDetails.errorCode);
                }
                if (!validation.validationSuccessful) System.exit(1);
            }
            try (var connection = DriverManager.getConnection(URL, "supportflow_app",
                    secrets.getProperty("SUPPORTFLOW_APP_PASSWORD"))) {
                if (!"supportflow_rebuild".equals(connection.getCatalog())) throw new IllegalStateException("Wrong database");
                boolean sufficient = true;
                for (String table : List.of("support_knowledge_article", "support_knowledge_revision", "support_knowledge_audit")) {
                    var privileges = new HashSet<String>();
                    try (var rows = connection.getMetaData().getTablePrivileges("supportflow_rebuild", null, table)) {
                        while (rows.next()) privileges.add(rows.getString("PRIVILEGE"));
                    }
                    boolean allowed = privileges.containsAll(table.endsWith("article")
                            ? Set.of("SELECT", "INSERT", "UPDATE") : Set.of("SELECT", "INSERT"));
                    boolean immutable = table.endsWith("article") || !privileges.contains("UPDATE");
                    boolean noDelete = !privileges.contains("DELETE");
                    sufficient &= allowed && immutable && noDelete;
                    System.out.println("TABLE=" + table + " REQUIRED_GRANTS=" + allowed
                            + " IMMUTABLE_PUBLICATION_AUDIT=" + immutable + " NO_DELETE=" + noDelete);
                }
                System.out.println("KNOWLEDGE_RUNTIME_GRANTS_VALID=" + sufficient);
                try (var sql = connection.createStatement();
                     var rows = sql.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version='10' AND success=TRUE")) {
                    if (rows.next()) {
                        String current = Files.readString(Path.of("src/main/resources/db/migration/V10__shop_knowledge_management.sql"));
                        String original = current.replaceAll(
                                "published_revision IS NOT NULL AND publication_slot IS NOT NULL\\R\\s+AND published_revision",
                                "published_revision");
                        System.out.println("V10_APPLIED_CHECKSUM=" + rows.getInt(1));
                        System.out.println("V10_CURRENT_SOURCE_CHECKSUM=" + checksum(current));
                        System.out.println("V10_ORIGINAL_SOURCE_CANDIDATE_CHECKSUM=" + checksum(original));
                    }
                }
                try (var sql = connection.createStatement();
                     var rows = sql.executeQuery("SELECT installed_rank, checksum, success FROM flyway_schema_history WHERE version='11'")) {
                    while (rows.next()) System.out.println("V11_HISTORY_RANK=" + rows.getInt(1)
                            + " CHECKSUM=" + rows.getInt(2) + " SUCCESS=" + rows.getBoolean(3));
                }
                try (var sql = connection.createStatement();
                     var rows = sql.executeQuery("SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS "
                             + "WHERE CONSTRAINT_SCHEMA='supportflow_rebuild' AND CONSTRAINT_NAME='ck_knowledge_article_publication'")) {
                    if (rows.next()) System.out.println("CURRENT_PUBLICATION_CONSTRAINT=" + rows.getString(1));
                }
                if (!sufficient) System.exit(1);
            }
        } catch (SQLException failure) {
            System.err.println("Local knowledge SQL failure: state=" + failure.getSQLState() + " code=" + failure.getErrorCode());
            System.exit(1);
        } catch (Exception failure) {
            System.err.println("Local knowledge setup stopped: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static int checksum(String source) {
        CRC32 crc = new CRC32();
        source.lines().forEach(line -> crc.update(line.getBytes(StandardCharsets.UTF_8)));
        return (int) crc.getValue();
    }
}
