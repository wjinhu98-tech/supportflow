package dev.supportflow.chat.conversation;

import dev.supportflow.chat.api.response.ChatResponse;
import dev.supportflow.chat.application.model.ChatReplyType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationContextRefTest {
    private static final Instant NOW = Instant.parse("2026-10-25T05:00:00Z");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void referencesAreBoundedImmutableValuesRatherThanBusinessFacts() {
        var inputs = new ArrayList<>(List.of("kb-11111111-1111-1111-1111-111111111111"));
        var context = ConversationContextRef.knowledge(inputs, NOW.plusSeconds(1800));
        inputs.clear();
        assertThat(context.sourceIds()).hasSize(1);
        assertThat(context.orderNo()).isNull();
        assertThatThrownBy(() -> context.sourceIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(json.writeValueAsString(context)).doesNotContain("buyerId", "tenantId", "totalAmount", "status", "content");
    }

    @Test
    void contextExpiresAtItsDeadlineAndCannotClaimAnUnboundedFutureLifetime() {
        assertThat(ConversationContextRef.order("SF10001", NOW.plusSeconds(1800)).activeAt(NOW)).isTrue();
        assertThat(ConversationContextRef.order("SF10001", NOW).activeAt(NOW)).isFalse();
        assertThat(ConversationContextRef.order("SF10001", NOW.minusMillis(1)).activeAt(NOW)).isFalse();
        assertThat(ConversationContextRef.order("SF10001", NOW.plusSeconds(1801)).activeAt(NOW)).isFalse();
        assertThat(ConversationContextRef.none().activeAt(NOW)).isFalse();
    }

    @Test
    void noneCannotSmuggleAnOrderOrKnowledgePointer() {
        assertThatThrownBy(() -> new ConversationContextRef(ConversationContextKind.NONE, "SF10001", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationContextRef(ConversationContextKind.NONE, null, List.of("test-source"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationContextRef(ConversationContextKind.NONE, null, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderReferenceCannotCarryKnowledgeAndMustBeAnExactBoundedIdentifier() {
        for (String number : List.of("", " ", "SF 10001", "SF\n10001", "SF\u00A010001", "x".repeat(65))) {
            assertThatThrownBy(() -> ConversationContextRef.order(number, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ConversationContextRef.order("SF10001", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationContextRef(ConversationContextKind.ORDER, "SF10001", List.of("test-source"), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void knowledgeReferencesRejectPathsDuplicatesEmptyAndUnboundedLists() {
        for (List<String> ids : List.of(List.<String>of(), List.of("../private"), List.of("https://invalid.example"),
                List.of("source-one", "source-one"), List.of("source-one", "source-two", "source-three"))) {
            assertThatThrownBy(() -> ConversationContextRef.knowledge(ids, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ConversationContextRef(ConversationContextKind.KNOWLEDGE, "SF10001", List.of("source-one"), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oldFourAndFiveFieldResponsesAndOldJsonStillHaveNoContext() {
        assertThat(new ChatResponse("old", ChatReplyType.TEXT, List.of(), null).context()).isNull();
        assertThat(new ChatResponse("old", ChatReplyType.TEXT, List.of(), null, null).context()).isNull();
        ChatResponse old = json.readValue("""
                {"reply":"old","type":"TEXT","orders":[],"nextAfterId":null,"knowledge":null}
                """, ChatResponse.class);
        assertThat(old.context()).isNull();
    }

    @Test
    void responseContextRoundTripsAsASnapshotWithTheOriginalDeadline() {
        var original = new ChatResponse("reply", ChatReplyType.TEXT, List.of(), null)
                .withContext(ConversationContextRef.order("SF10001", NOW.plusSeconds(1800)));
        ChatResponse restored = json.readValue(json.writeValueAsString(original), ChatResponse.class);
        assertThat(restored).isEqualTo(original);
        assertThat(restored.context().expiresAt()).isEqualTo(NOW.plusSeconds(1800));
    }

    @Test
    void falseResetRetainsTheHistoricalRequestFingerprintWhileTrueIsADifferentRequest() throws Exception {
        UUID id = UUID.randomUUID();
        var oldRequest = new ConversationMessageRequest(id, "查询此订单", "SF10001");
        var explicitFalse = new ConversationMessageRequest(id, "查询此订单", "SF10001", false);
        var explicitTrue = new ConversationMessageRequest(id, "查询此订单", "SF10001", true);
        String legacyPayload = "查询此订单".length() + ":查询此订单|" + "SF10001".length() + ":SF10001";
        String legacyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(legacyPayload.getBytes(StandardCharsets.UTF_8)));
        assertThat(JdbcConversationStore.hash(oldRequest)).isEqualTo(legacyHash)
                .isEqualTo(JdbcConversationStore.hash(explicitFalse))
                .isNotEqualTo(JdbcConversationStore.hash(explicitTrue));
    }

    @Test
    void resetFlagIsAnActualJsonBooleanAndOldRequestsDefaultToFalse() {
        String requestPrefix = "{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"message\":\"hello\"";
        assertThat(json.readValue(requestPrefix + "}", ConversationMessageRequest.class).resetContext()).isFalse();
        assertThat(json.readValue(requestPrefix + ",\"resetContext\":true}", ConversationMessageRequest.class).resetContext()).isTrue();
        for (String invalid : List.of("\"true\"", "1", "[]", "{}")) {
            assertThatThrownBy(() -> json.readValue(requestPrefix + ",\"resetContext\":" + invalid + "}", ConversationMessageRequest.class))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void cachedAndFreshReservationsKeepTheirOwnImmutableContext() {
        var context = ConversationContextRef.order("SF10001", NOW.plusSeconds(1800));
        assertThat(ConversationStore.Reservation.fresh(context).context()).isEqualTo(context);
        assertThat(ConversationStore.Reservation.fresh().context()).isEqualTo(ConversationContextRef.none());
        assertThat(new ConversationStore.Reservation(null).cached()).isFalse();
    }
}
