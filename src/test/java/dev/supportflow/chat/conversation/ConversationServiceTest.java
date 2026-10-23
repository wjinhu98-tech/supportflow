package dev.supportflow.chat.conversation;

import dev.supportflow.assistant.orchestration.ConversationOrchestrator;
import dev.supportflow.chat.api.response.ChatResponse;
import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationServiceTest {
    private static final long BUYER = 200;
    private static final long SHOP = 100;
    private static final UUID ID = UUID.fromString("0a652a13-d780-4ce9-b2d5-9468b825f571");
    private final CustomerPrincipal principal = new CustomerPrincipal("test-buyer", "unused",
            new CustomerIdentity(BUYER), List.of(SecurityPermission.ORDER_READ));
    private ConversationStore store;
    private ConversationOrchestrator orchestrator;
    private ConversationService service;
    private ConversationMessageRequest request;

    @BeforeEach
    void setup() {
        store = mock(ConversationStore.class);
        orchestrator = mock(ConversationOrchestrator.class);
        service = new ConversationService(store, orchestrator);
        request = new ConversationMessageRequest(UUID.randomUUID(), "查询订单", "SF10001");
    }

    @Test
    void newTurnReservesBeforeOrchestrationAndPersistsOnlyPublicResponse() {
        when(store.reserve(BUYER, SHOP, ID, request)).thenReturn(ConversationStore.Reservation.fresh());
        when(orchestrator.reply(principal, SHOP, request.message(), request.orderNo()))
                .thenReturn(ChatReply.text("你好"));
        ChatResponse response = ChatResponse.from(ChatReply.text("你好"));
        ConversationTurnResponse expected = completed();
        when(store.succeed(BUYER, SHOP, ID, request, response)).thenReturn(expected);

        assertThat(service.send(principal, SHOP, ID, request)).isSameAs(expected);

        var order = inOrder(store, orchestrator);
        order.verify(store).reserve(BUYER, SHOP, ID, request);
        order.verify(orchestrator).reply(principal, SHOP, request.message(), request.orderNo());
        order.verify(store).succeed(BUYER, SHOP, ID, request, response);
        verify(store, never()).fail(eq(BUYER), eq(SHOP), any(), any());
    }

    @Test
    void successfulRetryReturnsSavedSnapshotWithoutCallingOrchestrator() {
        ConversationTurnResponse cached = completed();
        when(store.reserve(BUYER, SHOP, ID, request)).thenReturn(new ConversationStore.Reservation(cached));

        assertThat(service.send(principal, SHOP, ID, request)).isSameAs(cached);

        verifyNoInteractions(orchestrator);
        verify(store, never()).succeed(eq(BUYER), eq(SHOP), eq(ID), any(), any());
    }

    @Test
    void ownershipOrIdempotencyConflictNeverExecutesBusinessLogic() {
        when(store.reserve(BUYER, SHOP, ID, request)).thenThrow(ConversationException.notFound());

        assertThatThrownBy(() -> service.send(principal, SHOP, ID, request))
                .isInstanceOf(ConversationException.class)
                .extracting(failure -> ((ConversationException) failure).status()).isEqualTo(HttpStatus.NOT_FOUND);

        verifyNoInteractions(orchestrator);
    }

    @Test
    void modelFailureIsMarkedTerminalRatherThanRetried() {
        when(store.reserve(BUYER, SHOP, ID, request)).thenReturn(ConversationStore.Reservation.fresh());
        when(orchestrator.reply(principal, SHOP, request.message(), request.orderNo()))
                .thenThrow(new ModelUnavailableException());

        assertThatThrownBy(() -> service.send(principal, SHOP, ID, request))
                .isInstanceOf(ModelUnavailableException.class);

        verify(store).fail(BUYER, SHOP, ID, request.clientMessageId());
        verify(store, never()).succeed(eq(BUYER), eq(SHOP), eq(ID), any(), any());
        verify(orchestrator).reply(principal, SHOP, request.message(), request.orderNo());
    }

    @Test
    void snapshotFailureAfterBusinessExecutionNeverReplaysBusinessLogic() {
        when(store.reserve(BUYER, SHOP, ID, request)).thenReturn(ConversationStore.Reservation.fresh());
        when(orchestrator.reply(principal, SHOP, request.message(), request.orderNo()))
                .thenReturn(ChatReply.text("已处理"));
        when(store.succeed(eq(BUYER), eq(SHOP), eq(ID), eq(request), any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> service.send(principal, SHOP, ID, request))
                .isInstanceOf(ConversationException.class).hasMessageContaining("核实处理结果");

        verify(store).fail(BUYER, SHOP, ID, request.clientMessageId());
        verify(orchestrator).reply(principal, SHOP, request.message(), request.orderNo());
    }

    @Test
    void failureMarkerOutageReturnsBoundedFriendlyErrorInsteadOfRepeatingCall() {
        when(store.reserve(BUYER, SHOP, ID, request)).thenReturn(ConversationStore.Reservation.fresh());
        when(orchestrator.reply(principal, SHOP, request.message(), request.orderNo()))
                .thenThrow(new IllegalStateException("private internal error"));
        doThrow(new DataAccessResourceFailureException("unreachable"))
                .when(store).fail(BUYER, SHOP, ID, request.clientMessageId());

        assertThatThrownBy(() -> service.send(principal, SHOP, ID, request))
                .isInstanceOf(ConversationException.class).hasMessageNotContaining("unreachable");

        verify(orchestrator).reply(principal, SHOP, request.message(), request.orderNo());
    }

    @Test
    void listAndRestoreOnlyReadOwnedStoreAndNeverCallModel() {
        UUID before = UUID.randomUUID();
        ConversationPage page = new ConversationPage(List.of(completed().conversation()), null);
        ConversationMessages messages = new ConversationMessages(completed().conversation(), List.of(), null);
        when(store.list(BUYER, SHOP, before)).thenReturn(page);
        when(store.messages(BUYER, SHOP, ID, before)).thenReturn(messages);

        assertThat(service.list(principal, SHOP, before)).isSameAs(page);
        assertThat(service.messages(principal, SHOP, ID, before)).isSameAs(messages);

        verifyNoInteractions(orchestrator);
    }

    @Test
    void createUsesAuthenticatedIdentityRatherThanRequestIdentity() {
        Conversation conversation = completed().conversation();
        when(store.create(BUYER, SHOP)).thenReturn(conversation);

        assertThat(service.create(principal, SHOP)).isSameAs(conversation);

        verifyNoInteractions(orchestrator);
    }

    @Test
    void orchestrationExplicitlySuspendsAnySurroundingTransaction() throws NoSuchMethodException {
        Transactional annotation = ConversationService.class
                .getMethod("send", CustomerPrincipal.class, long.class, UUID.class, ConversationMessageRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void requestHashIncludesExactMessageAndDistinguishesMissingOrderFromText() {
        UUID key = UUID.randomUUID();
        var first = new ConversationMessageRequest(key, "hello", null);
        assertThat(JdbcConversationStore.hash(first)).hasSize(64)
                .isEqualTo(JdbcConversationStore.hash(new ConversationMessageRequest(UUID.randomUUID(), "hello", null)))
                .isNotEqualTo(JdbcConversationStore.hash(new ConversationMessageRequest(key, "hello ", null)))
                .isNotEqualTo(JdbcConversationStore.hash(new ConversationMessageRequest(key, "hello", "-")));
    }

    private static ConversationTurnResponse completed() {
        Instant time = Instant.parse("2026-10-23T08:00:00Z");
        return new ConversationTurnResponse(new Conversation(ID, SHOP, time, time), List.of());
    }
}
