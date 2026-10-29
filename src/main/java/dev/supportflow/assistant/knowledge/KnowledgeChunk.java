package dev.supportflow.assistant.knowledge;

import java.util.ArrayList;
import java.util.List;

/** Chunks are retrieval units only; the response keeps the complete, versioned original source. */
record KnowledgeChunk(int ordinal, String text) {
    static List<KnowledgeChunk> split(KnowledgeDocument source) {
        String content = source.content().replaceAll("(?m)^原文：https://[^\\r\\n]+$", "");
        if (content.length() > 16_384) throw new IllegalArgumentException("Knowledge source exceeds chunking bound");
        String title = prefix(source.title(), 80);
        int[] points = content.codePoints().toArray();
        var chunks = new ArrayList<KnowledgeChunk>();
        for (int start = 0; start < points.length;) {
            int end = Math.min(points.length, start + 270);
            chunks.add(new KnowledgeChunk(chunks.size(), title + "\n" + new String(points, start, end - start)));
            if (end == points.length) break;
            start = end - 32;
        }
        return List.copyOf(chunks);
    }

    private static String prefix(String text, int count) {
        return text.codePointCount(0, text.length()) <= count ? text : text.substring(0, text.offsetByCodePoints(0, count));
    }
}
