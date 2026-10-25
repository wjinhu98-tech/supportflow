package dev.supportflow.merchant.knowledge;

import java.time.Instant;

record StoredKnowledge(String id, long shopId, long sequenceId, KnowledgeDraft draft, KnowledgeStatus status,
                       int version, int lastRevision, Integer publishedRevision, Integer publicationSlot,
                       Instant createdAt, Instant updatedAt) { }
