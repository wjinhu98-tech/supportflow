package dev.supportflow.testsupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** AI admission reads handoff visibility and mutates its buyer/shop mutex; it cannot write human history. */
public final class HumanSupportFixtures {
    private HumanSupportFixtures() { }

    public static void grantAiAdmission(Connection connection, String database, String runtime) throws SQLException {
        if (database == null || runtime == null || !database.matches("[a-z_]+") || !runtime.matches("[a-z_]+")) {
            throw new IllegalArgumentException("Unexpected isolated test identifier");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("GRANT SELECT ON `" + database + "`.`support_human_session` TO '" + runtime + "'@'%'");
            statement.execute("GRANT SELECT (id, deleted_at) ON `" + database + "`.`support_chat_conversation` TO '" + runtime + "'@'%'");
            statement.execute("GRANT SELECT, INSERT, UPDATE ON `" + database + "`.`support_chat_scope` TO '" + runtime + "'@'%'");
        }
    }
}
