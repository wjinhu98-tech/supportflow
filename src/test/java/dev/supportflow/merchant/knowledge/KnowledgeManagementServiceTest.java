package dev.supportflow.merchant.knowledge;

import dev.supportflow.assistant.aftersale.AssistantBusinessException;
import dev.supportflow.common.api.error.ErrorCode;
import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import dev.supportflow.merchant.MerchantAccessService;
import dev.supportflow.merchant.MerchantShopAccess;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeManagementServiceTest {
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final MerchantAccessService access = mock(MerchantAccessService.class);
    private final KnowledgeManagementService service = new KnowledgeManagementService(repository, access);
    private final CustomerPrincipal editor = new CustomerPrincipal("editor", "unused", new CustomerIdentity(996),
            List.of(SecurityPermission.KNOWLEDGE_MANAGE), AccountType.MERCHANT_STAFF);
    private final UUID id = UUID.randomUUID();
    private final KnowledgeDraft draft = new KnowledgeDraft("包装说明", List.of("礼盒"), "礼盒为蓝色。");

    @Test
    void deniedAuthorizationStopsBeforeReadingOrWritingKnowledge() {
        when(access.lockKnowledgeShop(editor, 100)).thenThrow(new AccessDeniedException("denied"));
        assertThatThrownBy(() -> service.publish(editor, 100, id, 0)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void createUsesServerIdentityAndAuditsWithinTheWriteFlow() {
        StoredKnowledge created = stored(0, KnowledgeStatus.DRAFT, 0, null, null);
        when(access.lockKnowledgeShop(editor, 100)).thenReturn(new MerchantShopAccess(4, 100));
        when(repository.find(eq(100L), anyString(), eq(false))).thenReturn(Optional.of(created));
        service.create(editor, 100, draft);
        var ordered = inOrder(access, repository);
        ordered.verify(access).lockKnowledgeShop(editor, 100);
        ordered.verify(repository).create(eq(100L), argThat(value -> UUID.fromString(value) != null), eq(draft));
        ordered.verify(repository).find(eq(100L), anyString(), eq(false));
        ordered.verify(repository).audit(created, KnowledgeAction.CREATED, "editor", 4);
    }

    @Test
    void staleVersionCannotSaveOrAddAudit() {
        when(repository.find(100, id.toString(), true)).thenReturn(Optional.of(stored(2, KnowledgeStatus.DRAFT, 0, null, null)));
        assertThatThrownBy(() -> service.save(editor, 100, id, 1, draft))
                .isInstanceOfSatisfying(AssistantBusinessException.class, error ->
                        assertThat(error.code()).isEqualTo(ErrorCode.KNOWLEDGE_CONFLICT));
        verify(repository, never()).save(any(), any());
        verify(repository, never()).audit(any(), any(), anyString(), anyLong());
    }

    @Test
    void savePublishedDraftDoesNotCreateOrReplacePublishedSnapshot() {
        StoredKnowledge before = stored(1, KnowledgeStatus.PUBLISHED, 1, 1, 5);
        StoredKnowledge after = stored(2, KnowledgeStatus.PUBLISHED, 1, 1, 5);
        when(access.lockKnowledgeShop(editor, 100)).thenReturn(new MerchantShopAccess(4, 100));
        when(repository.find(100, id.toString(), true)).thenReturn(Optional.of(before));
        when(repository.find(100, id.toString(), false)).thenReturn(Optional.of(after));
        when(repository.save(before, draft)).thenReturn(true);
        service.save(editor, 100, id, 1, draft);
        verify(repository).audit(after, KnowledgeAction.SAVED, "editor", 4);
        verify(repository, never()).publish(any(), anyInt());
        verify(repository, never()).revision(any(), anyInt(), anyString(), anyLong());
    }

    @Test
    void publishClaimsFirstFreeSlotThenAddsImmutableRevisionAndAudit() {
        StoredKnowledge before = stored(0, KnowledgeStatus.DRAFT, 0, null, null);
        StoredKnowledge after = stored(1, KnowledgeStatus.PUBLISHED, 1, 1, 3);
        when(access.lockKnowledgeShop(editor, 100)).thenReturn(new MerchantShopAccess(4, 100));
        when(repository.find(100, id.toString(), true)).thenReturn(Optional.of(before));
        when(repository.find(100, id.toString(), false)).thenReturn(Optional.of(after));
        when(repository.occupiedSlots(100)).thenReturn(Set.of(1, 2, 4));
        when(repository.publish(before, 3)).thenReturn(true);
        service.publish(editor, 100, id, 0);
        var ordered = inOrder(access, repository);
        ordered.verify(access).lockKnowledgeShop(editor, 100);
        ordered.verify(repository).find(100, id.toString(), true);
        ordered.verify(repository).occupiedSlots(100);
        ordered.verify(repository).publish(before, 3);
        ordered.verify(repository).revision(before, 1, "editor", 4);
        ordered.verify(repository).find(100, id.toString(), false);
        ordered.verify(repository).audit(after, KnowledgeAction.PUBLISHED, "editor", 4);
    }

    @Test
    void hundredPublishedArticlesRequireExplicitOfflineBeforeAnotherPublication() {
        when(repository.find(100, id.toString(), true)).thenReturn(Optional.of(stored(0, KnowledgeStatus.DRAFT, 0, null, null)));
        when(repository.occupiedSlots(100)).thenReturn(IntStream.rangeClosed(1, 100).boxed().collect(Collectors.toSet()));
        assertThatThrownBy(() -> service.publish(editor, 100, id, 0))
                .isInstanceOfSatisfying(AssistantBusinessException.class, error ->
                        assertThat(error.code()).isEqualTo(ErrorCode.KNOWLEDGE_LIMIT_REACHED));
        verify(repository, never()).publish(any(), anyInt());
        verify(repository, never()).revision(any(), anyInt(), anyString(), anyLong());
    }

    @Test
    void concurrentSlotCollisionBecomesConflictRatherThanOverwritingAnotherArticle() {
        StoredKnowledge before = stored(0, KnowledgeStatus.DRAFT, 0, null, null);
        when(repository.find(100, id.toString(), true)).thenReturn(Optional.of(before));
        when(repository.occupiedSlots(100)).thenReturn(Set.of());
        when(repository.publish(before, 1)).thenThrow(new DuplicateKeyException("slot already claimed"));
        assertThatThrownBy(() -> service.publish(editor, 100, id, 0))
                .isInstanceOfSatisfying(AssistantBusinessException.class, error ->
                        assertThat(error.code()).isEqualTo(ErrorCode.KNOWLEDGE_CONFLICT));
        verify(repository, never()).revision(any(), anyInt(), anyString(), anyLong());
        verify(repository, never()).audit(any(), any(), anyString(), anyLong());
    }

    @Test
    void republishingExistingArticleKeepsItsReservedSlot() {
        StoredKnowledge before = stored(2, KnowledgeStatus.PUBLISHED, 1, 1, 5);
        when(access.lockKnowledgeShop(editor, 100)).thenReturn(new MerchantShopAccess(4, 100));
        when(repository.find(100, id.toString(), true)).thenReturn(Optional.of(before));
        when(repository.find(100, id.toString(), false)).thenReturn(Optional.of(stored(3, KnowledgeStatus.PUBLISHED, 2, 2, 5)));
        when(repository.publish(before, 5)).thenReturn(true);
        service.publish(editor, 100, id, 2);
        verify(repository, never()).occupiedSlots(anyLong());
        verify(repository).revision(before, 2, "editor", 4);
    }

    @Test
    void offlineDraftConflictsWithoutChangingDraftOrCreatingAudit() {
        when(repository.find(100, id.toString(), true)).thenReturn(Optional.of(stored(0, KnowledgeStatus.DRAFT, 0, null, null)));
        assertThatThrownBy(() -> service.offline(editor, 100, id, 0)).isInstanceOf(AssistantBusinessException.class);
        verify(repository, never()).offline(any());
        verify(repository, never()).audit(any(), any(), anyString(), anyLong());
    }

    @Test
    void offlineRemovesActivePointerButDoesNotDeleteHistoricalRevisions() {
        StoredKnowledge before = stored(1, KnowledgeStatus.PUBLISHED, 1, 1, 5);
        StoredKnowledge after = stored(2, KnowledgeStatus.OFFLINE, 1, null, null);
        when(access.lockKnowledgeShop(editor, 100)).thenReturn(new MerchantShopAccess(4, 100));
        when(repository.find(100, id.toString(), true)).thenReturn(Optional.of(before));
        when(repository.find(100, id.toString(), false)).thenReturn(Optional.of(after));
        when(repository.offline(before)).thenReturn(true);
        service.offline(editor, 100, id, 1);
        verify(repository).audit(after, KnowledgeAction.OFFLINE, "editor", 4);
        verify(repository, never()).revision(any(), anyInt(), anyString(), anyLong());
    }

    @Test
    void unknownCursorIsScopedToTheAuthorizedShop() {
        when(repository.sequence(100, id.toString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(editor, 100, id))
                .isInstanceOfSatisfying(AssistantBusinessException.class, error ->
                        assertThat(error.code()).isEqualTo(ErrorCode.KNOWLEDGE_NOT_FOUND));
        verify(access).requireKnowledgeShop(editor, 100);
        verify(repository, never()).list(anyLong(), anyLong());
    }

    @Test
    void listReturnsTwentyItemsAndOpaqueKeysetCursorWithoutReturningExtraRow() {
        var rows = IntStream.range(0, 21).mapToObj(index -> new StoredKnowledge(UUID.randomUUID().toString(), 100,
                100 - index, draft, KnowledgeStatus.DRAFT, 0, 0, null, null, Instant.EPOCH, Instant.EPOCH)).toList();
        when(repository.list(100, Long.MAX_VALUE)).thenReturn(rows);
        when(repository.view(any())).thenAnswer(invocation -> {
            StoredKnowledge row = invocation.getArgument(0);
            return new KnowledgeArticle(row.id(), 100, draft.title(), draft.keywords(), draft.content(),
                    row.status(), 0, null, null, Instant.EPOCH, Instant.EPOCH);
        });
        var result = service.list(editor, 100, null);
        assertThat(result.items()).hasSize(20);
        assertThat(result.nextBeforeId()).isEqualTo(rows.get(19).id());
        verify(repository, times(20)).view(any());
    }

    private StoredKnowledge stored(int version, KnowledgeStatus status, int lastRevision,
                                    Integer publishedRevision, Integer slot) {
        return new StoredKnowledge(id.toString(), 100, 1, draft, status, version, lastRevision,
                publishedRevision, slot, Instant.EPOCH, Instant.EPOCH);
    }
}
