package dev.supportflow.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabDeploymentToolTest {
    @TempDir Path temporary;

    @Test
    void createsSeparateRandomCredentialsAndPreservesThemOnRerun() throws Exception {
        Path directory = temporary.resolve("private");
        LabDeploymentTool.prepare(directory);
        String root = Files.readString(directory.resolve("lab_root_password"));
        String app = Files.readString(directory.resolve("lab_app_password"));
        String buyer = Files.readString(directory.resolve("lab_buyer_password"));
        String hash = Files.readString(directory.resolve("lab_buyer_hash"));
        assertThat(root.matches("[A-Za-z0-9_-]{43}")).isTrue();
        assertThat(!root.equals(app) && !root.equals(buyer) && !app.equals(buyer)).isTrue();
        assertThat(PasswordEncoderFactories.createDelegatingPasswordEncoder().matches(buyer, hash)).isTrue();
        LabDeploymentTool.prepare(directory);
        assertThat(Files.readString(directory.resolve("lab_root_password")).equals(root)).isTrue();
    }

    @Test
    void incompleteCredentialsAreNeverSilentlyOverwritten() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("partial"));
        Files.writeString(directory.resolve("lab_root_password"), "existing-value");
        assertThatThrownBy(() -> LabDeploymentTool.prepare(directory)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(directory.resolve("lab_root_password"))).isEqualTo("existing-value");
    }

    @Test
    void migrationTargetIsTheIsolatedDatabaseWithNoDailyHostOrSchema() {
        assertThat(LabDeploymentTool.JDBC_URL).startsWith("jdbc:mysql://mysql:3306/supportflow_lab?")
                .doesNotContain("supportflow_rebuild", "127.0.0.1", "localhost");
    }
}
