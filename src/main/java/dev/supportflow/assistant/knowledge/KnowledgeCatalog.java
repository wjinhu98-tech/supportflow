package dev.supportflow.assistant.knowledge;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Loads bounded UTF-8 resources packaged with the application; no file upload or network access. */
final class KnowledgeCatalog {

    private static final int MAX_DOCUMENT_BYTES = 16_384;

    List<KnowledgeDocument> load() {
        Properties metadata = new Properties();
        try (InputStream input = new ClassPathResource("knowledge/catalog.properties").getInputStream()) {
            metadata.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load the local knowledge catalog", exception);
        }
        String catalog = required(metadata, "documents");
        List<KnowledgeDocument> documents = new ArrayList<>();
        Set<String> sources = new HashSet<>();
        for (String entry : catalog.split(",")) {
            String sourceId = entry.strip();
            // Validate before constructing the classpath resource; catalog entries cannot traverse directories.
            if (!sourceId.matches("[a-z][a-z0-9-]{1,63}") || !sources.add(sourceId)) {
                throw new IllegalStateException("Invalid or duplicate knowledge sourceId");
            }
            String content;
            try (InputStream input = new ClassPathResource("knowledge/" + sourceId + ".md").getInputStream()) {
                byte[] bytes = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
                if (bytes.length > MAX_DOCUMENT_BYTES) {
                    throw new IllegalStateException("Knowledge document exceeds its byte limit");
                }
                content = new String(bytes, StandardCharsets.UTF_8).strip();
            } catch (IOException exception) {
                throw new IllegalStateException("Could not load knowledge source " + sourceId, exception);
            }
            documents.add(new KnowledgeDocument(sourceId, required(metadata, sourceId + ".version"),
                    required(metadata, sourceId + ".title"),
                    Arrays.stream(required(metadata, sourceId + ".keywords").split(","))
                            .map(String::strip).toList(), content));
        }
        return List.copyOf(documents);
    }

    private String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing knowledge catalog property " + key);
        }
        return value.strip();
    }
}
