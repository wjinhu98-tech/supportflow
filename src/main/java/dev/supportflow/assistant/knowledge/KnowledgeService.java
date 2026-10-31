package dev.supportflow.assistant.knowledge;

import dev.supportflow.chat.application.model.KnowledgeEvidence;
import dev.supportflow.chat.application.model.KnowledgeSource;
import dev.supportflow.chat.application.model.KnowledgeStatus;
import dev.supportflow.merchant.knowledge.KnowledgeDraft;
import dev.supportflow.merchant.knowledge.KnowledgeLimits;
import dev.supportflow.merchant.knowledge.KnowledgeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Scoped answers retain complete conditions; immutable source snapshots are kept separately for audit. */
@Service
public class KnowledgeService {

    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final String UNKNOWN = "这个问题目前没有足够依据，我目前不知道。请补充具体情况，或联系店铺客服。";
    private final KnowledgeRetriever retriever;
    private final List<KnowledgeDocument> catalog;
    private final KnowledgeRepository managed;
    private final RagKnowledgeSearch rag;
    private final PublicKnowledgeSelector publicSelector;
    private final PublicReferenceCatalog publicReferences = new PublicReferenceCatalog();
    private static final String UNAVAILABLE = "当前服务暂时不可用，请稍后重试或联系店铺客服。";
    private static final Pattern PROTECTED = Pattern.compile(
            "售后|退款|退货|收货|审批|到账|权限|密码|密钥|订单状态|物流|快递|运单|api.?key|系统提示|system.?prompt|"
                    + "refund|after.?sale|order.{0,4}status|receipt|approv|payment",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTRUCTION = Pattern.compile(
            "忽略.{0,24}(规则|指令|要求)|绕过|直接执行|工具调用|已执行|执行成功|ignore.{0,40}(instruction|rule)|"
                    + "tool.?call|system\\s*:|assistant\\s*:", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MONEY_FACT = Pattern.compile(
            "金额|总价|实付|应付|order.{0,8}(amount|total|owner)|total.?amount", Pattern.CASE_INSENSITIVE);
    private static final Pattern OWNERSHIP_FACT = Pattern.compile(
            "归属|买家(?:ID|身份|编号)|客户(?:ID|身份|编号)|用户(?:ID|身份|编号)|谁的订单|buyer.?id", Pattern.CASE_INSENSITIVE);
    private static final Pattern PUBLIC_FEE = Pattern.compile("运费|配送费|礼盒|包装");
    private static final Pattern TRANSACTION_AMOUNT_REFERENCE = Pattern.compile(
            "(?:当前|本次|这次|该次|这笔|该笔|本笔|此笔|本单|这单|该单|此单)\\s*(?:的\\s*)?(?:实际\\s*)?(?:金额|总价|实付|应付)"
                    + "|\\b(?:current|this)\\s+(?:order\\s+)?(?:amount|total|paid)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER_MONEY_CONTEXT = Pattern.compile(
            "订单|实付|应付|支付|付款|交易|order|total.?amount", Pattern.CASE_INSENSITIVE);

    public KnowledgeService() {
        this(new KnowledgeCatalog().load());
    }

    public KnowledgeService(KnowledgeRepository managed) {
        catalog = new KnowledgeCatalog().load();
        retriever = new KnowledgeRetriever(catalog);
        this.managed = managed;
        rag = null;
        publicSelector = null;
    }

    public KnowledgeService(KnowledgeRepository managed, RagKnowledgeSearch rag) {
        this(managed, rag, (PublicKnowledgeSelector) null);
    }

    @Autowired
    public KnowledgeService(KnowledgeRepository managed, RagKnowledgeSearch rag, ObjectProvider<PublicKnowledgeSelector> selectors) {
        this(managed, rag, selectors.getIfAvailable());
    }

    KnowledgeService(KnowledgeRepository managed, RagKnowledgeSearch rag, PublicKnowledgeSelector publicSelector) {
        catalog = new KnowledgeCatalog().load();
        retriever = new KnowledgeRetriever(catalog);
        this.managed = managed;
        this.rag = rag;
        this.publicSelector = publicSelector;
    }

    KnowledgeService(List<KnowledgeDocument> documents) {
        catalog = List.copyOf(documents);
        retriever = new KnowledgeRetriever(catalog);
        managed = null;
        rag = null;
        publicSelector = null;
    }

    /** Public evidence supports tests and future trace views; content is immutable and versioned. */
    public List<KnowledgeMatch> retrieve(String message) {
        if (message == null || message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) return List.of();
        return retriever.retrieve(message);
    }

    public String answer(String message) {
        List<KnowledgeMatch> matches = retrieve(message);
        return renderDetailed(matches).reply();
    }

    /** Merchant-authored prose cannot override application-controlled business policy. */
    public String answer(long shopId, String message) {
        if (isProtected(message) || managed == null || !valid(message)) return answer(message);
        try {
            var documents = new ArrayList<>(catalog);
            documents.addAll(shopDocuments(shopId));
            return renderDetailed(search(shopId, documents, message)).reply();
        } catch (RuntimeException unavailable) {
            // Never use another shop, a silently truncated corpus or a model-generated substitute.
            return UNAVAILABLE;
        }
    }

    /** A reliable shop FAQ match may answer an ordinary question without invoking the business agent. */
    public Optional<String> tryAnswer(long shopId, String message) {
        if (managed == null || !valid(message) || isProtected(message)) return Optional.empty();
        try {
            List<KnowledgeMatch> matches = search(shopId, shopDocuments(shopId), message);
            return matches.isEmpty() ? Optional.empty() : Optional.of(renderDetailed(matches).reply());
        } catch (RuntimeException unavailable) {
            return Optional.of(UNAVAILABLE);
        }
    }

    /** Direct answers and separate immutable audit snapshots; never omit conditions to fit the reply. */
    public KnowledgeAnswer answerDetailed(long shopId, String message) {
        if (isProtected(message) || managed == null || !valid(message)) {
            return renderDetailed(retrieve(message));
        }
        try {
            var documents = new ArrayList<>(catalog);
            documents.addAll(shopDocuments(shopId));
            return renderDetailed(shopId, message, search(shopId, documents, message));
        } catch (RuntimeException unavailable) {
            return unavailableAnswer();
        }
    }

    /** Empty means the existing general/business route should continue; failures never invent evidence. */
    public Optional<KnowledgeAnswer> tryAnswerDetailed(long shopId, String message) {
        if (managed == null || !valid(message) || isProtected(message)) return Optional.empty();
        try {
            List<KnowledgeMatch> matches = search(shopId, shopDocuments(shopId), message);
            return matches.isEmpty() ? Optional.empty() : Optional.of(renderDetailed(shopId, message, matches));
        } catch (RuntimeException unavailable) {
            return Optional.of(unavailableAnswer());
        }
    }

    /** Resolve one remembered public source against current scoped publications, never historical text. */
    public KnowledgeAnswer followUpDetailed(long shopId, String message, List<String> sourceIds) {
        if (!valid(message) || sourceIds == null || sourceIds.size() != 1
                || sourceIds.getFirst() == null || !sourceIds.getFirst().matches("[a-z][a-z0-9-]{1,63}")) {
            return renderDetailed(List.of());
        }
        if (isProtected(message)) return answerDetailed(shopId, message);
        try {
            String requested = sourceIds.getFirst();
            List<KnowledgeDocument> current = requested.startsWith("kb-") || requested.startsWith("ref-")
                    ? managed == null ? List.of() : shopDocuments(shopId) : catalog;
            var matches = current.stream().filter(document -> document.sourceId().equals(requested))
                    .map(document -> new KnowledgeMatch(document.sourceId(), document.version(), document.title(),
                            document.content(), 0, List.of("current-scoped-context-reference"))).toList();
            return renderDetailed(matches);
        } catch (RuntimeException unavailable) {
            return unavailableAnswer();
        }
    }

    private static KnowledgeAnswer unavailableAnswer() {
        return new KnowledgeAnswer(UNAVAILABLE, new KnowledgeEvidence(KnowledgeStatus.UNAVAILABLE, List.of()));
    }

    /** Evidence lookup uses only local CPU embeddings and current scoped publications. */
    public KnowledgeAnswer searchEvidence(long shopId, String message) {
        if (!valid(message)) return renderDetailed(List.of());
        if (isProtected(message)) return renderDetailed(retrieve(message));
        try {
            var documents = new ArrayList<>(catalog);
            documents.addAll(shopDocuments(shopId));
            return renderDetailed(search(shopId, documents, message));
        } catch (RuntimeException unavailable) { return unavailableAnswer(); }
    }

    private List<KnowledgeMatch> search(long shopId, List<KnowledgeDocument> documents, String message) {
        return rag == null ? new KnowledgeRetriever(documents).retrieve(retrievalQuery(message))
                : rag.search(shopId, documents, retrievalQuery(message));
    }

    /** Management counts/builds exactly the same current publication set used by scoped retrieval. */
    public KnowledgeIndexStatus index(long shopId, boolean warmup) {
        if (rag == null || !rag.enabled()) return new KnowledgeIndexStatus("DISABLED", 0, 0, null);
        var documents = new ArrayList<>(catalog);
        documents.addAll(shopDocuments(shopId));
        return rag.index(shopId, documents, warmup);
    }

    private KnowledgeAnswer renderDetailed(long shopId, String message, List<KnowledgeMatch> matches) {
        if (rag != null && rag.publicSelectionEnabled() && publicSelector != null && !isProtected(message)
                && !matches.isEmpty() && matches.stream().allMatch(match -> publicReferences.containsExact(shopId, match))) {
            try {
                var selection = publicSelector.select(shopId, message, matches);
                if (selection.isPresent()) {
                    List<String> ids = selection.get();
                    Set<String> allowed = matches.stream().map(KnowledgeMatch::sourceId).collect(java.util.stream.Collectors.toSet());
                    if (ids.size() <= 2 && ids.stream().distinct().count() == ids.size() && allowed.containsAll(ids))
                        return renderDetailed(matches.stream().filter(match -> ids.contains(match.sourceId())).toList());
                }
            } catch (RuntimeException unavailable) {
                // The original scoped evidence remains usable; never expose remote failures or rerun business work.
            }
        }
        return renderDetailed(matches);
    }

    private KnowledgeAnswer renderDetailed(List<KnowledgeMatch> matches) {
        if (matches.isEmpty()) {
            return new KnowledgeAnswer(UNKNOWN, new KnowledgeEvidence(KnowledgeStatus.NO_MATCH, List.of()));
        }
        var sources = matches.stream().map(match -> new KnowledgeSource(match.sourceId(), match.version(),
                match.title(), match.content())).toList();
        String reply = String.join("\n\n", matches.stream().map(this::answerContent).toList()).strip();
        // A hidden audit snapshot cannot serve as the customer's missing eligibility conditions.
        // Keep every condition in the visible answer, or defer instead of selectively promising.
        if (reply.isBlank() || reply.length() > KnowledgeAnswer.MAX_REPLY_LENGTH) {
            reply = "这个问题涉及的条件较多，暂时无法准确答复，请联系店铺客服核实。";
        }
        return new KnowledgeAnswer(reply, new KnowledgeEvidence(KnowledgeStatus.ANSWERED, sources));
    }

    private String answerContent(KnowledgeMatch match) {
        // Body headings can contain eligibility conditions, so only canonical catalog metadata is projected away.
        return publicReferences.answerContent(match).orElse(match.content()).strip();
    }

    private List<KnowledgeDocument> shopDocuments(long shopId) {
        if (shopId <= 0) throw new IllegalArgumentException("Shop ID is required for knowledge retrieval");
        var snapshots = managed.publishedForShop(shopId);
        if (snapshots.size() > KnowledgeLimits.MAX_PUBLISHED) {
            throw new IllegalStateException("Published corpus exceeds the enforced shop bound");
        }
        var documents = new ArrayList<>(snapshots.stream()
                .filter(source -> !isProtected(source.title() + " " + String.join(" ", source.keywords())
                        + " " + source.content()))
                .map(source -> {
                    var checked = new KnowledgeDraft(source.title(), source.keywords(), source.content());
                    if (source.revision() <= 0) throw new IllegalStateException("Invalid published revision");
                    return new KnowledgeDocument("kb-" + source.articleId(), "v" + source.revision(),
                            checked.title(), checked.keywords(), checked.content());
                }).toList());
        if (rag != null) documents.addAll(rag.references(shopId));
        return List.copyOf(documents);
    }

    private static boolean valid(String message) {
        return message != null && !message.isBlank() && message.length() <= MAX_MESSAGE_LENGTH;
    }

    private static boolean isProtected(String message) {
        return message != null && (PROTECTED.matcher(message).find() || INSTRUCTION.matcher(message).find()
                || isOrderFactQuestion(message));
    }

    /** Actual order amounts and ownership are database facts, not merchant-authored FAQ evidence. */
    public static boolean isOrderFactQuestion(String message) {
        if (message == null) return false;
        if (OWNERSHIP_FACT.matcher(message).find()) return true;
        // A public-fee keyword elsewhere must not launder a claim about this transaction's amount.
        // Explicit fee subjects such as "礼盒金额" and "公开运费金额" remain ordinary public FAQs.
        if (TRANSACTION_AMOUNT_REFERENCE.matcher(message).find()) return true;
        return MONEY_FACT.matcher(message).find()
                && (!PUBLIC_FEE.matcher(message).find() || ORDER_MONEY_CONTEXT.matcher(message).find());
    }

    private static String retrievalQuery(String message) {
        // The command prefix is not subject-matter evidence. Otherwise every unmatched FAQ would cite
        // the project-scope document simply because it contains the generic word "knowledge base".
        return message.replace("知识库", " ").strip();
    }

}
