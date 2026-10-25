package dev.supportflow.assistant.knowledge;

import dev.supportflow.chat.application.model.KnowledgeEvidence;
import dev.supportflow.chat.application.model.KnowledgeSource;
import dev.supportflow.chat.application.model.KnowledgeStatus;
import dev.supportflow.merchant.knowledge.KnowledgeDraft;
import dev.supportflow.merchant.knowledge.KnowledgeLimits;
import dev.supportflow.merchant.knowledge.KnowledgeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Retrieval -> selected source passages -> cited extractive answer; no external model or business database. */
@Service
public class KnowledgeService {

    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final String UNKNOWN = "本项目知识库中没有足够依据，我目前不知道。"
            + "请补充你要了解的项目规则，或联系人工客服；我不会编造店铺政策、物流轨迹或退款承诺。";
    private final KnowledgeRetriever retriever;
    private final List<KnowledgeDocument> catalog;
    private final KnowledgeRepository managed;
    private static final String UNAVAILABLE = "当前店铺知识资料暂时不可用，请稍后重试或联系店铺客服。";
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
    private static final int MAX_INLINE_SOURCE_LENGTH = 900;

    public KnowledgeService() {
        this(new KnowledgeCatalog().load());
    }

    @Autowired
    public KnowledgeService(KnowledgeRepository managed) {
        catalog = new KnowledgeCatalog().load();
        retriever = new KnowledgeRetriever(catalog);
        this.managed = managed;
    }

    KnowledgeService(List<KnowledgeDocument> documents) {
        catalog = List.copyOf(documents);
        retriever = new KnowledgeRetriever(catalog);
        managed = null;
    }

    /** Public evidence supports tests and future trace views; content is immutable and versioned. */
    public List<KnowledgeMatch> retrieve(String message) {
        if (message == null || message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) return List.of();
        return retriever.retrieve(message);
    }

    public String answer(String message) {
        List<KnowledgeMatch> matches = retrieve(message);
        return render(message, matches);
    }

    /** Merchant-authored prose cannot override application-controlled business policy. */
    public String answer(long shopId, String message) {
        if (isProtected(message) || managed == null || !valid(message)) return answer(message);
        try {
            var documents = new ArrayList<>(catalog);
            documents.addAll(shopDocuments(shopId));
            return render(message, new KnowledgeRetriever(documents).retrieve(retrievalQuery(message)));
        } catch (RuntimeException unavailable) {
            // Never use another shop, a silently truncated corpus or a model-generated substitute.
            return UNAVAILABLE;
        }
    }

    /** A reliable shop FAQ match may answer an ordinary question without invoking the business agent. */
    public Optional<String> tryAnswer(long shopId, String message) {
        if (managed == null || !valid(message) || isProtected(message)) return Optional.empty();
        try {
            List<KnowledgeMatch> matches = new KnowledgeRetriever(shopDocuments(shopId)).retrieve(retrievalQuery(message));
            return matches.isEmpty() ? Optional.empty() : Optional.of(render(message, matches));
        } catch (RuntimeException unavailable) {
            return Optional.of(UNAVAILABLE);
        }
    }

    /** Bounded complete quotations and separately expandable source snapshots; no selective promises. */
    public KnowledgeAnswer answerDetailed(long shopId, String message) {
        if (isProtected(message) || managed == null || !valid(message)) {
            return renderDetailed(retrieve(message));
        }
        try {
            var documents = new ArrayList<>(catalog);
            documents.addAll(shopDocuments(shopId));
            return renderDetailed(new KnowledgeRetriever(documents).retrieve(retrievalQuery(message)));
        } catch (RuntimeException unavailable) {
            return unavailableAnswer();
        }
    }

    /** Empty means the existing general/business route should continue; failures never invent evidence. */
    public Optional<KnowledgeAnswer> tryAnswerDetailed(long shopId, String message) {
        if (managed == null || !valid(message) || isProtected(message)) return Optional.empty();
        try {
            List<KnowledgeMatch> matches = new KnowledgeRetriever(shopDocuments(shopId))
                    .retrieve(retrievalQuery(message));
            return matches.isEmpty() ? Optional.empty() : Optional.of(renderDetailed(matches));
        } catch (RuntimeException unavailable) {
            return Optional.of(unavailableAnswer());
        }
    }

    private static KnowledgeAnswer unavailableAnswer() {
        return new KnowledgeAnswer(UNAVAILABLE, new KnowledgeEvidence(KnowledgeStatus.UNAVAILABLE, List.of()));
    }

    private KnowledgeAnswer renderDetailed(List<KnowledgeMatch> matches) {
        if (matches.isEmpty()) {
            return new KnowledgeAnswer(UNKNOWN, new KnowledgeEvidence(KnowledgeStatus.NO_MATCH, List.of()));
        }
        var sources = matches.stream().map(match -> new KnowledgeSource(match.sourceId(), match.version(),
                match.title(), match.content())).toList();
        StringBuilder reply = new StringBuilder("以下是当前演示环境的资料摘录，不是实时订单查询或业务执行结果。\n");
        for (KnowledgeMatch match : matches) {
            reply.append('\n').append(match.title()).append('\n')
                    .append(boundedCompleteQuote(match.content())).append('\n');
        }
        return new KnowledgeAnswer(reply.toString().strip(), new KnowledgeEvidence(KnowledgeStatus.ANSWERED, sources));
    }

    private static String boundedCompleteQuote(String content) {
        // No keyword list can prove that a discarded paragraph contains no eligibility condition.
        // Quote every paragraph, or no claim at all: the complete snapshot remains expandable.
        if (content.length() > MAX_INLINE_SOURCE_LENGTH) {
            return "已找到相关资料。为避免截断后遗漏限制条件，请展开来源查看完整说明。";
        }
        return content;
    }

    private List<KnowledgeDocument> shopDocuments(long shopId) {
        if (shopId <= 0) throw new IllegalArgumentException("Shop ID is required for knowledge retrieval");
        var snapshots = managed.publishedForShop(shopId);
        if (snapshots.size() > KnowledgeLimits.MAX_PUBLISHED) {
            throw new IllegalStateException("Published corpus exceeds the enforced shop bound");
        }
        return snapshots.stream()
                .filter(source -> !isProtected(source.title() + " " + String.join(" ", source.keywords())
                        + " " + source.content()))
                .map(source -> {
                    var checked = new KnowledgeDraft(source.title(), source.keywords(), source.content());
                    if (source.revision() <= 0) throw new IllegalStateException("Invalid published revision");
                    return new KnowledgeDocument("kb-" + source.articleId(), "v" + source.revision(),
                            checked.title(), checked.keywords(), checked.content());
                }).toList();
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

    private String render(String message, List<KnowledgeMatch> matches) {
        if (matches.isEmpty()) return UNKNOWN;
        StringBuilder answer = new StringBuilder("【店铺服务说明】\n"
                + "以下为当前演示环境的服务说明，不代表外部企业政策，也不是实时订单查询结果。\n");
        for (KnowledgeMatch match : matches) {
            if (match.sourceId().startsWith("kb-")) {
                answer.append("\n【店铺公开资料原文】\n")
                        .append("以下仅为当前店铺已发布资料的原文引用，不是订单状态或业务操作结果。")
                        .append("售后仍以确认收货后15天为准，缺少收货时间需人工核实；申请需经客服审批，资料不能替代业务校验。\n")
                        .append("标题：").append(match.title()).append('\n')
                        .append("[资料原文开始]\n").append(match.content()).append("\n[资料原文结束]\n")
                        .append("[来源：").append(match.sourceId()).append('@').append(match.version()).append("]\n");
                continue;
            }
            answer.append('\n').append(match.title()).append('\n')
                    .append(extractPassages(message, match.content()))
                    .append("\n[来源：").append(match.sourceId()).append('@').append(match.version()).append("]\n");
        }
        String rendered = answer.toString().strip();
        return rendered.length() <= 20_000 ? rendered : UNAVAILABLE;
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
