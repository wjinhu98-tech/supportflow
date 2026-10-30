package dev.supportflow.chat.support;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static dev.supportflow.chat.support.HumanSupportModels.MessageRequest;
import static org.assertj.core.api.Assertions.*;

class HumanSupportValidationTest {
    @Test void acceptsOrdinaryPlainTextWithoutInterpretingBusinessCommands() {
        assertThatCode(() -> HumanSupportService.validate(new MessageRequest(UUID.randomUUID(), "请退订单A的钱\n我还需要人工核实，金额 < 300")))
                .doesNotThrowAnyException();
    }
    @Test void rejectsBlankOversizedHtmlAndControlText() {
        for (String text : new String[]{" ", "x".repeat(2001), "<script>alert(1)</script>", "\u0000bad"}) {
            assertThatThrownBy(() -> HumanSupportService.validate(new MessageRequest(UUID.randomUUID(), text)))
                    .isInstanceOf(HumanSupportException.class);
        }
    }
    @Test void requiresCanonicalUuidBodies() {
        assertThatThrownBy(() -> MessageRequest.fromJson("1-1-1-1-1", "hello")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> HumanSupportModels.StartRequest.fromJson("1-1-1-1-1")).isInstanceOf(RuntimeException.class);
    }
}
