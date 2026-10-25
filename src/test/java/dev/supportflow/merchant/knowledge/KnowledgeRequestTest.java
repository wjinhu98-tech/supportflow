package dev.supportflow.merchant.knowledge;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeRequestTest {
    private final JsonMapper json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Test
    void expectedVersionMustBeAnActualNonnegativeBoundedJsonInteger() {
        assertThat(json.readValue("{\"expectedVersion\":0}", KnowledgeManagementController.VersionRequest.class)
                .expectedVersion()).isZero();
        for (String value : new String[]{"null", "-1", "1.5", "0.0", "\"0\"", "true", "{}", "1000000000", "9999999999999999999999999"}) {
            assertThatThrownBy(() -> json.readValue("{\"expectedVersion\":" + value + "}",
                    KnowledgeManagementController.VersionRequest.class)).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> json.readValue("{}", KnowledgeManagementController.VersionRequest.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void unknownKnowledgeFieldsAreRejectedWithoutChangingGlobalLegacyJsonBehavior() {
        assertThatThrownBy(() -> json.readValue("""
                {"title":"title","keywords":["key"],"content":"body","staffId":999}
                """, KnowledgeManagementController.CreateRequest.class)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue("""
                {"expectedVersion":0,"title":"title","keywords":["key"],"content":"body","actor":"forged"}
                """, KnowledgeManagementController.SaveRequest.class)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue("{\"expectedVersion\":0,\"actor\":\"forged\"}",
                KnowledgeManagementController.VersionRequest.class)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void validSaveBindsExpectedVersionAndPureTextFields() {
        var saved = json.readValue("""
                {"expectedVersion":2,"title":"title","keywords":["key"],"content":"body"}
                """, KnowledgeManagementController.SaveRequest.class);
        assertThat(saved.expectedVersion()).isEqualTo(2);
        assertThat(saved.content()).isEqualTo("body");
        assertThat(saved.keywords()).containsExactly("key");
    }
}
