-- CHECK accepts UNKNOWN in MySQL. Keep an explicit non-null publication invariant for upgraded instances.
-- Existing snapshots and article contents are unchanged; malformed existing rows fail this migration visibly.
ALTER TABLE support_knowledge_article
    DROP CHECK ck_knowledge_article_publication,
    ADD CONSTRAINT ck_knowledge_article_publication CHECK ((
      (status='DRAFT' AND last_revision=0 AND published_revision IS NULL AND publication_slot IS NULL)
      OR (status='OFFLINE' AND last_revision>0 AND published_revision IS NULL AND publication_slot IS NULL)
      OR (status='PUBLISHED' AND published_revision IS NOT NULL AND publication_slot IS NOT NULL
          AND published_revision=last_revision AND last_revision>0 AND publication_slot BETWEEN 1 AND 100)
    ) IS TRUE);
