package dev.supportflow.testsupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Knowledge privileges are explicit; append-only revisions and audit never receive UPDATE or DELETE. */
public final class KnowledgeFixtures {
    private KnowledgeFixtures() { }

    public static void grantRead(Connection connection, String database, String runtime) throws SQLException {
        grant(connection, database, runtime, false);
    }

    public static void grantManagement(Connection connection, String database, String runtime) throws SQLException {
        grant(connection, database, runtime, true);
    }

    private static void grant(Connection connection, String database, String runtime, boolean writable)
            throws SQLException {
        if (!database.matches("[a-z_]+") || !runtime.matches("[a-z_]+")) {
            throw new IllegalArgumentException("Unexpected isolated test identifier");
        }
        try (Statement sql = connection.createStatement()) {
            for (String table : List.of("support_knowledge_article", "support_knowledge_revision", "support_knowledge_audit")) {
                String permissions = !writable ? "SELECT" : table.endsWith("_article")
                        ? "SELECT,INSERT,UPDATE" : "SELECT,INSERT";
                sql.execute("GRANT " + permissions + " ON " + database + "." + table + " TO '" + runtime + "'@'%'");
            }
        }
    }
}
