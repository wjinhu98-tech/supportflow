package dev.supportflow.merchant.knowledge;

import java.util.List;

public record KnowledgePage<T>(List<T> items, String nextBeforeId) {
    public KnowledgePage { items = List.copyOf(items); }
}
