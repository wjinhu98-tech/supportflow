package dev.supportflow.assistant.knowledge;

/** A normalized dense sentence embedding, distinct from lexical hashes or keyword expansion. */
interface KnowledgeEmbedding extends AutoCloseable {
    float[] embed(String text, boolean query);
    @Override default void close() { }
}
