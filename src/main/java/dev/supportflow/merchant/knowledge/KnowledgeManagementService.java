package dev.supportflow.merchant.knowledge;

import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.merchant.MerchantAccessService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeManagementService {
    private final KnowledgeRepository repository;
    private final MerchantAccessService access;

    public KnowledgeManagementService(KnowledgeRepository repository, MerchantAccessService access) {
        this.repository = repository;
        this.access = access;
    }

    @Transactional
    public KnowledgeArticle create(CustomerPrincipal principal, long shopId, KnowledgeDraft draft) {
        var authorization = access.lockKnowledgeShop(principal, shopId);
        String id = UUID.randomUUID().toString();
        repository.create(shopId, id, draft);
        StoredKnowledge saved = owned(shopId, id, false);
        repository.audit(saved, KnowledgeAction.CREATED, principal.getUsername(), authorization.staffId());
        return repository.view(saved);
    }

    @Transactional(readOnly = true)
    public KnowledgePage<KnowledgeArticle> list(CustomerPrincipal principal, long shopId, UUID beforeId) {
        access.requireKnowledgeShop(principal, shopId);
        long sequence = beforeId == null ? Long.MAX_VALUE
                : repository.sequence(shopId, beforeId.toString()).orElseThrow(KnowledgeException::notFound);
        List<StoredKnowledge> found = repository.list(shopId, sequence);
        var selected = found.stream().limit(KnowledgeLimits.PAGE_SIZE).toList();
        return new KnowledgePage<>(selected.stream().map(repository::view).toList(),
                found.size() > KnowledgeLimits.PAGE_SIZE ? selected.getLast().id() : null);
    }

    @Transactional(readOnly = true)
    public KnowledgeArticle get(CustomerPrincipal principal, long shopId, UUID id) {
        access.requireKnowledgeShop(principal, shopId);
        return repository.view(owned(shopId, id.toString(), false));
    }

    @Transactional
    public KnowledgeArticle save(CustomerPrincipal principal, long shopId, UUID id, int expectedVersion,
                                 KnowledgeDraft draft) {
        var authorization = access.lockKnowledgeShop(principal, shopId);
        StoredKnowledge stored = expected(shopId, id, expectedVersion);
        if (!repository.save(stored, draft)) throw KnowledgeException.conflict();
        StoredKnowledge saved = owned(shopId, id.toString(), false);
        repository.audit(saved, KnowledgeAction.SAVED, principal.getUsername(), authorization.staffId());
        return repository.view(saved);
    }

    @Transactional
    public KnowledgeArticle publish(CustomerPrincipal principal, long shopId, UUID id, int expectedVersion) {
        var authorization = access.lockKnowledgeShop(principal, shopId);
        StoredKnowledge stored = expected(shopId, id, expectedVersion);
        int slot = stored.publicationSlot() == null ? freeSlot(shopId) : stored.publicationSlot();
        try {
            // UNIQUE(shop,slot) and CHECK(slot<=100) enforce the corpus bound even for concurrent publishers.
            // Competing articles claiming the same free slot return 409, never silently overwrite or truncate.
            if (!repository.publish(stored, slot)) throw KnowledgeException.conflict();
            repository.revision(stored, stored.lastRevision() + 1, principal.getUsername(), authorization.staffId());
        } catch (DuplicateKeyException conflict) {
            throw KnowledgeException.conflict();
        }
        StoredKnowledge published = owned(shopId, id.toString(), false);
        repository.audit(published, KnowledgeAction.PUBLISHED, principal.getUsername(), authorization.staffId());
        return repository.view(published);
    }

    @Transactional
    public KnowledgeArticle offline(CustomerPrincipal principal, long shopId, UUID id, int expectedVersion) {
        var authorization = access.lockKnowledgeShop(principal, shopId);
        StoredKnowledge stored = expected(shopId, id, expectedVersion);
        if (stored.status() != KnowledgeStatus.PUBLISHED || !repository.offline(stored)) throw KnowledgeException.conflict();
        StoredKnowledge saved = owned(shopId, id.toString(), false);
        repository.audit(saved, KnowledgeAction.OFFLINE, principal.getUsername(), authorization.staffId());
        return repository.view(saved);
    }

    @Transactional(readOnly = true)
    public KnowledgePage<KnowledgeAudit> audit(CustomerPrincipal principal, long shopId, UUID id, Long beforeId) {
        access.requireKnowledgeShop(principal, shopId);
        owned(shopId, id.toString(), false);
        if (beforeId != null && beforeId <= 0) throw KnowledgeException.invalid();
        List<KnowledgeAudit> found = repository.audit(shopId, id.toString(), beforeId == null ? Long.MAX_VALUE : beforeId);
        var selected = found.stream().limit(KnowledgeLimits.PAGE_SIZE).toList();
        return new KnowledgePage<>(selected, found.size() > KnowledgeLimits.PAGE_SIZE ? selected.getLast().id() : null);
    }

    private StoredKnowledge owned(long shopId, String id, boolean lock) {
        return repository.find(shopId, id, lock).orElseThrow(KnowledgeException::notFound);
    }

    private StoredKnowledge expected(long shopId, UUID id, int expectedVersion) {
        if (expectedVersion < 0 || expectedVersion >= KnowledgeLimits.MAX_VERSION) throw KnowledgeException.invalid();
        StoredKnowledge stored = owned(shopId, id.toString(), true);
        if (stored.version() != expectedVersion) throw KnowledgeException.conflict();
        return stored;
    }

    private int freeSlot(long shopId) {
        var occupied = repository.occupiedSlots(shopId);
        for (int slot = 1; slot <= KnowledgeLimits.MAX_PUBLISHED; slot++) {
            if (!occupied.contains(slot)) return slot;
        }
        throw KnowledgeException.limit();
    }
}
