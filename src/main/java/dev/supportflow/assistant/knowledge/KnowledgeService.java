package dev.supportflow.assistant.knowledge;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Retrieval -> selected source passages -> cited extractive answer; no external model or business database. */
@Service
public class KnowledgeService {

    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final String UNKNOWN = "本项目知识库中没有足够依据，我目前不知道。"
            + "请补充你要了解的项目规则，或联系人工客服；我不会编造店铺政策、物流轨迹或退款承诺。";
    private final KnowledgeRetriever retriever;

    public KnowledgeService() {
        this(new KnowledgeCatalog().load());
    }

    KnowledgeService(List<KnowledgeDocument> documents) {
        retriever = new KnowledgeRetriever(documents);
    }

    /** Public evidence supports tests and future trace views; content is immutable and versioned. */
    public List<KnowledgeMatch> retrieve(String message) {
        if (message == null || message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) return List.of();
        return retriever.retrieve(message);
    }

    public String answer(String message) {
        List<KnowledgeMatch> matches = retrieve(message);
        if (matches.isEmpty()) return UNKNOWN;
        StringBuilder answer = new StringBuilder("【本项目学习版知识说明】\n"
                + "以下内容来自本地版本化文档，不代表外部企业政策，也不是实时订单查询结果。\n");
        for (KnowledgeMatch match : matches) {
            answer.append('\n').append(match.title()).append('\n')
                    .append(extractPassages(message, match.content()))
                    .append("\n[来源：").append(match.sourceId()).append('@').append(match.version()).append("]\n");
        }
        return answer.toString().strip();
    }

    private String extractPassages(String message, String content) {
        // Paragraph boundaries survive line wrapping; headings are not factual passages.
        List<String> passages = Arrays.stream(content.split("\\R\\s*\\R")).map(String::strip)
                .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        if (passages.isEmpty()) return content;
        Set<String> queryTerms = KnowledgeRetriever.tokenize(message);
        List<Integer> ranked = new ArrayList<>();
        for (int index = 1; index < passages.size(); index++) ranked.add(index);
        ranked.sort(Comparator.<Integer>comparingLong(index -> KnowledgeRetriever.tokenize(passages.get(index))
                .stream().filter(queryTerms::contains).count()).reversed().thenComparingInt(Integer::intValue));
        List<Integer> selected = new ArrayList<>();
        selected.add(0); // Always retain the document's scope and learning-version qualification.
        ranked.stream().limit(2).forEach(selected::add);
        selected.sort(Integer::compareTo);
        return String.join("\n", selected.stream().map(passages::get).toList());
    }
}
