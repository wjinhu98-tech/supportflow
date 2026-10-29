package dev.supportflow.merchant.knowledge;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keep accepted uploads in memory, including when tests replace the YAML configuration location. */
@Configuration(proxyBeanMethods = false)
public class KnowledgeImportConfiguration {
    @Bean
    MultipartConfigElement knowledgeMultipartConfig() {
        return new MultipartConfigElement("", KnowledgeImportFileParser.MAX_FILE_BYTES, 128 * 1024, 128 * 1024);
    }
}
