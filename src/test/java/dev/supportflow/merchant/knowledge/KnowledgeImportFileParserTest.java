package dev.supportflow.merchant.knowledge;

import dev.supportflow.assistant.aftersale.AssistantBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class KnowledgeImportFileParserTest {
    private final KnowledgeImportFileParser parser = new KnowledgeImportFileParser();
    @Test void normalizationKeepsLiteralMarkupAndStableContentIdentity() {
        var first = parser.parse(file("说明.md", "\uFEFF # 杯子\r\n<script>alert(1)</script>\r\n"));
        var second = parser.parse(file("改名.txt", "# 杯子\n<script>alert(1)</script>"));
        assertThat(first.content()).isEqualTo("# 杯子\n<script>alert(1)</script>");
        assertThat(first.hash()).isEqualTo(second.hash()).hasSize(64);
        assertThat(KnowledgeImportService.articleId(100, first.hash())).isNotEqualTo(KnowledgeImportService.articleId(101, first.hash()));
    }
    @ParameterizedTest @ValueSource(strings={"a.pdf", "a.html", "../a.txt", "C:\\a.txt", "a.exe"})
    void invalidFileNamesAndUnsupportedTypesDoNotBecomeKnowledge(String name) {
        assertThatThrownBy(() -> parser.parse(file(name, "正文"))).isInstanceOf(AssistantBusinessException.class);
    }
    @ParameterizedTest @ValueSource(strings={"", " ", "\u0000"})
    void emptyOrControlTextIsRejected(String text) {
        assertThatThrownBy(() -> parser.parse(file("a.txt", text))).isInstanceOf(AssistantBusinessException.class);
    }
    @Test void invalidUtf8AndOversizedBodyAreRejectedWithoutTruncation() {
        assertThatThrownBy(() -> parser.parse(new MockMultipartFile("file","a.txt","text/plain",new byte[]{(byte)0xc3,0x28})))
                .isInstanceOf(AssistantBusinessException.class);
        assertThatThrownBy(() -> parser.parse(file("a.txt", "字".repeat(4001)))).isInstanceOf(AssistantBusinessException.class);
        assertThatThrownBy(() -> parser.parse(file("a.txt", "x".repeat(65537)))).isInstanceOf(AssistantBusinessException.class);
        assertThat(parser.parse(file("a.txt", "字".repeat(4000))).content()).hasSize(4000);
    }
    private static MockMultipartFile file(String name, String body) {
        return new MockMultipartFile("file",name,"text/plain",body.getBytes(StandardCharsets.UTF_8));
    }
}
