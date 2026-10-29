import dev.supportflow.common.security.database.DatabaseAccountAdministration;
import dev.supportflow.common.security.local.LocalCustomerAccountsProperties;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Explicit local operator commands; never expose an account-administration HTTP endpoint. */
public final class LocalSecurityAccounts {
    public static void main(String[] args) {
        try {
            if (!Files.isRegularFile(Path.of("src/main/resources/db/migration/V20__database_accounts.sql"))) {
                throw new IllegalArgumentException("Run from repository root");
            }
            if (args.length < 1 || args.length > 2
                    || !java.util.Set.of("import-missing", "disable", "enable", "revoke", "reset-password").contains(args[0])) {
                throw new IllegalArgumentException("Expected account command");
            }
            if (!args[0].equals("import-missing")
                    && (args.length != 2 || !args[1].matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))) {
                throw new IllegalArgumentException("Expected exact account username");
            }
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource(".local/secrets.yml"));
            var secrets = yaml.getObject();
            String password = secrets == null ? null : secrets.getProperty("SUPPORTFLOW_MIGRATOR_PASSWORD");
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("Missing operator credential");
            }
            var data = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/supportflow_rebuild?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=5000&socketTimeout=15000",
                    "supportflow_migrator", password);
            var jdbc = new JdbcTemplate(data);
            if (!"supportflow_rebuild".equals(jdbc.queryForObject("SELECT DATABASE()", String.class))) {
                throw new IllegalStateException("Wrong database");
            }
            var accounts = new DatabaseAccountAdministration(jdbc);
            if (args[0].equals("import-missing")) {
                if (args.length != 1) {
                    throw new IllegalArgumentException("No extra import arguments");
                }
                var environment = new StandardEnvironment();
                environment.getPropertySources().addFirst(new MapPropertySource("local-account-import",
                        Map.of("spring.profiles.active", "local")));
                ConfigDataEnvironmentPostProcessor.applyTo(environment, null, null, "local");
                var configured = Binder.get(environment)
                        .bind("supportflow.security.local", LocalCustomerAccountsProperties.class)
                        .get();
                System.out.println("ACCOUNTS_IMPORTED=" + accounts.importMissingConfiguredAccounts(configured.accounts()));
                return;
            }
            boolean changed = switch (args[0]) {
                case "disable" -> accounts.disable(args[1]);
                case "enable" -> accounts.enable(args[1]);
                case "revoke" -> accounts.invalidateSessions(args[1]);
                case "reset-password" -> accounts.changePassword(args[1], System.getenv("SUPPORTFLOW_NEW_PASSWORD"),
                        PasswordEncoderFactories.createDelegatingPasswordEncoder());
                default -> throw new IllegalArgumentException("Invalid operation");
            };
            System.out.println("ACCOUNT_CHANGED=" + changed);
        } catch (Exception failure) {
            System.err.println("ACCOUNT_COMMAND_FAILED type=" + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
