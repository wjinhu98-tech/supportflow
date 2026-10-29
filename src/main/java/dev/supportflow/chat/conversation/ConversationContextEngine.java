package dev.supportflow.chat.conversation;

import dev.supportflow.assistant.knowledge.KnowledgeAnswer;
import dev.supportflow.assistant.knowledge.KnowledgeService;
import dev.supportflow.assistant.orchestration.ConversationOrchestrator;
import dev.supportflow.assistant.orchestration.ConversationRouter;
import dev.supportflow.assistant.orchestration.AssistantRoute;
import dev.supportflow.assistant.planning.ExplicitOrderNumbers;
import dev.supportflow.assistant.runtime.store.AgentRunKey;
import dev.supportflow.assistant.runtime.store.AgentRunInput;
import dev.supportflow.chat.api.response.ChatResponse;
import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.chat.application.model.ChatReplyType;
import dev.supportflow.chat.application.model.KnowledgeStatus;
import dev.supportflow.chat.application.ProductOrderQuestion;
import dev.supportflow.chat.application.model.OrderSelectionIntent;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.order.application.OrderQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded reference resolution, not a language-model memory or an execution authorization. */
@Service
public class ConversationContextEngine {
    private static final Pattern NO_INHERITED_ARGUMENTS = Pattern.compile(
            "退款|退货|售后|申请|提交|确认|取消|修改|支付|付款|审批|授权|密码|密钥|执行|"
                    + "忽略|绕过|system\\s*:|assistant\\s*:|ignore|refund|approve|cancel|delete|update|payment",
            Pattern.CASE_INSENSITIVE);
    private static final String ORDER_REFERENCE_TERMS = "这单|那单|这笔|那笔|这一单|那一单|本单|该单|此单|本笔|该笔|此笔";
    private static final Pattern REFERENCE = Pattern.compile(ORDER_REFERENCE_TERMS
            + "|刚才|刚刚|前面|上面|上次|之前|它|这张|那张|这种|那种|这个|那个|^(?:那|那么)");
    private static final Pattern ORDER_READ_SUBJECT = Pattern.compile("状态|发货|物流|快递|运单|到哪|金额|总价|实付|应付|多少钱|价格|进度");
    private static final Pattern ORDER_SUBJECT = Pattern.compile("订单|查单|" + ORDER_REFERENCE_TERMS);
    private static final Pattern PUBLIC_PRICE_SUBJECT = Pattern.compile("礼盒|贺卡|包装|运费|配送费|商品|产品");
    private static final Pattern PRICE_QUESTION = Pattern.compile(
            "多少钱|价格|单价|报价|费用|收费|(?:运费|配送费|包装费).{0,6}多少|多少.{0,6}(?:运费|配送费|包装费)");
    private static final Pattern TRANSACTION_PRICE = Pattern.compile("实付|应付|总价|交易金额|交易总额");
    private static final Pattern LOGISTICS_SUBJECT = Pattern.compile("物流|快递|运单|到哪|配送进度|包裹");
    private static final Pattern SHORT_ORDER_READ = Pattern.compile(
            "^(?:那|那么|现在|目前)?(?:发货了吗|是什么状态|状态怎么样|金额是多少|总价是多少|物流到哪了|查物流|查订单状态|订单状态|查一下状态"
                    + "|(?:快递|物流)(?:信息|进度|轨迹|到哪里了|到哪了|什么时候(?:能)?到|怎么样))[？?！!。\\s]*$");
    private static final Pattern KNOWLEDGE_FOLLOWUP = Pattern.compile(
            "怎么|如何|可以|能|填写|使用|说明|颜色|材质|保养|洗护|尺码|介绍|还有|具体");

    private final ConversationOrchestrator orchestrator;
    private final KnowledgeService knowledge;
    private final OrderQueryService orders;
    private final ConversationRouter router;
    private final Clock clock;

    @Autowired
    public ConversationContextEngine(ConversationOrchestrator orchestrator, KnowledgeService knowledge,
                                     OrderQueryService orders, ConversationRouter router) {
        this(orchestrator, knowledge, orders, router, Clock.systemUTC());
    }

    ConversationContextEngine(ConversationOrchestrator orchestrator, KnowledgeService knowledge,
                              OrderQueryService orders, ConversationRouter router, Clock clock) {
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.knowledge = Objects.requireNonNull(knowledge);
        this.orders = Objects.requireNonNull(orders);
        this.router = Objects.requireNonNull(router);
        this.clock = Objects.requireNonNull(clock);
    }

    public ChatResponse reply(CustomerPrincipal principal, long shopId, ConversationMessageRequest request,
                              ConversationContextRef previous) {
        return reply(principal, shopId, request, previous, null);
    }

    public ChatResponse reply(CustomerPrincipal principal, long shopId, ConversationMessageRequest request,
                              ConversationContextRef previous, AgentRunKey runKey) {
        Instant now = clock.instant();
        var context = request.resetContext() || previous == null || !previous.activeAt(now)
                ? ConversationContextRef.none() : previous;
        String message = request.message().strip();
        // Sensitive/write intent keeps the original explicit-input path. A remembered subject is never consent.
        if (NO_INHERITED_ARGUMENTS.matcher(message).find()) {
            return enrich(orchestrate(principal, shopId, request.message(), request.orderNo(), runKey, request),
                    ConversationContextRef.none(), now, shopId);
        }
        Set<String> explicit = ExplicitOrderNumbers.extract(message);
        if (explicit.size() > 1) return clarify("你提到了多笔订单，请先明确要查询哪一笔。 ");
        if (explicit.isEmpty()) {
            if (ProductOrderQuestion.isPublicShippingQuestion(message)) {
                return knowledgeResponse(knowledge.answerDetailed(shopId, request.message()), now, shopId);
            }
            var product = ProductOrderQuestion.parse(message);
            if (product.isPresent()) return productReply(principal, shopId, product.get(), request, runKey, now);
        }
        String explicitOrder = explicit.isEmpty() ? request.orderNo() : explicit.iterator().next();
        boolean reference = REFERENCE.matcher(message).find();
        boolean readSubject = ORDER_READ_SUBJECT.matcher(message).find();
        boolean publicPriceQuestion = explicit.isEmpty() && PUBLIC_PRICE_SUBJECT.matcher(message).find()
                && PRICE_QUESTION.matcher(message).find() && !ORDER_SUBJECT.matcher(message).find()
                && !TRANSACTION_PRICE.matcher(message).find() && !KnowledgeService.isOrderFactQuestion(message);
        if (publicPriceQuestion && router.route(message) != AssistantRoute.KNOWLEDGE) {
            // An explicit product/public fee is a new subject, not the selected order's transaction amount.
            // A miss must not silently fall back to that old amount or ask a model to invent a price.
            var current = knowledge.tryAnswerDetailed(shopId, request.message());
            return current.isPresent() ? knowledgeResponse(current.get(), now, shopId)
                    : clarify("当前资料没有这项商品价格或公开费用的可靠说明，请明确商品或费用项目，或联系商铺客服核实。");
        }
        boolean boundedOrderRead = (reference && readSubject) || SHORT_ORDER_READ.matcher(message).matches()
                || explicitOrder != null && readSubject;
        // The UI may still send yesterday's selected order with a newly named product FAQ.
        // A reliable current non-business subject may replace it; a bounded order read still wins.
        boolean lookedUpCurrentKnowledge = explicit.isEmpty() && !boundedOrderRead
                && !ORDER_SUBJECT.matcher(message).find() && !KnowledgeService.isOrderFactQuestion(message);
        Optional<KnowledgeAnswer> currentKnowledge = lookedUpCurrentKnowledge
                ? knowledge.tryAnswerDetailed(shopId, request.message()) : Optional.empty();
        if (currentKnowledge.isPresent()) return knowledgeResponse(currentKnowledge.get(), now, shopId);

        if (router.route(message) != AssistantRoute.KNOWLEDGE && boundedOrderRead
                && (explicitOrder != null || context.kind() == ConversationContextKind.ORDER
                || context.kind() == ConversationContextKind.NONE)) {
            String number = explicitOrder != null ? explicitOrder : context.orderNo();
            if (number == null) return clarify("请先选择或明确一笔当前店铺的订单，我才能查询最新信息。");
            // The reference itself grants nothing. Recheck the current authenticated buyer and shop first.
            orders.getOwnedOrder(principal.getIdentity(), shopId, number);
            String command = LOGISTICS_SUBJECT.matcher(message).find() ? "查物流" : "查询此订单";
            ChatResponse response = orchestrate(principal, shopId, command, number, runKey, request);
            return response.withContext(ConversationContextRef.order(number, expiry(now)));
        }

        if (explicitOrder == null && reference) {
            // A newly explicit FAQ subject in this very message outranks the remembered source.
            var current = lookedUpCurrentKnowledge ? currentKnowledge
                    : knowledge.tryAnswerDetailed(shopId, request.message());
            if (current.isPresent()) return knowledgeResponse(current.get(), now, shopId);
            if (!KNOWLEDGE_FOLLOWUP.matcher(message).find()
                    || context.kind() != ConversationContextKind.KNOWLEDGE) {
                return clarify("请说明你指的是哪件商品或哪条资料，我不会猜测上文对象。");
            }
            if (context.sourceIds().size() != 1) {
                return clarify("上一条涉及多份资料，请明确你想继续咨询哪一个主题。");
            }
            return knowledgeResponse(knowledge.followUpDetailed(shopId, request.message(), context.sourceIds()), now, shopId);
        }

        // Explicit FAQ subjects may change the topic; pronouns alone are never a new subject.
        if (!lookedUpCurrentKnowledge && explicitOrder == null && !reference
                && !ORDER_SUBJECT.matcher(message).find() && !KnowledgeService.isOrderFactQuestion(message)) {
            var current = knowledge.tryAnswerDetailed(shopId, request.message());
            if (current.isPresent()) return knowledgeResponse(current.get(), now, shopId);
        }
        ChatResponse response = orchestrate(principal, shopId, request.message(), explicitOrder, runKey, request);
        return enrich(response, context, now, shopId);
    }

    private ChatResponse productReply(CustomerPrincipal principal, long shopId, ProductOrderQuestion question,
                                      ConversationMessageRequest request, AgentRunKey key, Instant now) {
        if (!question.validKeyword()) return clarify("请提供2至100字、最多4个关键词的商品名称或规格，或从「订单」选择要咨询的订单。");
        var matches = orders.findOwnedOrdersByProduct(principal.getIdentity(), shopId, question.keyword());
        if (matches.items().isEmpty()) {
            return clarify("暂未找到本店包含「" + question.keyword() + "」且有商品信息的本人订单。"
                    + "请换一个商品名称，或从「订单」选择；历史订单可能尚未补齐商品信息。");
        }
        if (matches.items().size() > 1 || matches.hasMore()) {
            String text = "找到多笔包含「" + question.keyword() + "」的订单，请根据商品规格和下单时间选择一笔。"
                    + (matches.hasMore() ? "匹配较多，先展示20笔；可补充更具体的商品名称或规格。" : "");
            return ChatResponse.from(ChatReply.productSelection(text, matches.items(), question.intent()))
                    .withContext(ConversationContextRef.none());
        }
        String number = matches.items().getFirst().orderNo();
        String command = question.intent() == OrderSelectionIntent.LOGISTICS ? "查物流" : "查询此订单";
        ChatResponse result = orchestrate(principal, shopId, command, number, key, request);
        return new ChatResponse("以下是包含「" + question.keyword() + "」的订单信息（整单状态）：\n" + result.reply(),
                result.type(), result.orders(), result.nextAfterId(), result.knowledge(),
                ConversationContextRef.order(number, expiry(now)), result.selectionIntent());
    }

    private ChatResponse orchestrate(CustomerPrincipal principal, long shopId, String message, String orderNo,
                                     AgentRunKey runKey, ConversationMessageRequest request) {
        if (runKey != null && router.route(message) == AssistantRoute.LOGISTICS) {
            return ChatResponse.from(orchestrator.reply(principal, shopId, message, orderNo,
                    new AgentRunInput(runKey, request, message, orderNo)));
        }
        return ChatResponse.from(orchestrator.reply(principal, shopId, message, orderNo));
    }

    private ChatResponse knowledgeResponse(KnowledgeAnswer answer, Instant now, long shopId) {
        return enrich(ChatResponse.from(ChatReply.knowledge(answer.reply(), answer.evidence())),
                ConversationContextRef.none(), now, shopId);
    }

    private ChatResponse enrich(ChatResponse response, ConversationContextRef previous, Instant now, long shopId) {
        if (response.knowledge() != null) {
            if (response.knowledge().status() != KnowledgeStatus.ANSWERED) {
                return response.withContext(ConversationContextRef.none());
            }
            return response.withContext(ConversationContextRef.knowledge(response.knowledge().sources().stream()
                    .map(source -> source.sourceId()).toList(), expiry(now)));
        }
        if (response.type() == ChatReplyType.ORDER_DETAILS && response.orders().size() == 1) {
            if (response.orders().getFirst().shopId() != shopId) {
                throw new IllegalArgumentException("Order context cannot cross the requested shop");
            }
            return response.withContext(ConversationContextRef.order(response.orders().getFirst().orderNo(), expiry(now)));
        }
        if (response.type() == ChatReplyType.ORDER_SELECTION || response.type() == ChatReplyType.AFTER_SALE_FORM) {
            return response.withContext(ConversationContextRef.none());
        }
        // Greetings can retain a still-valid pointer, but cannot validate a supplied order or renew its TTL.
        return response.withContext(previous);
    }

    private static ChatResponse clarify(String message) {
        return ChatResponse.from(ChatReply.text(message.strip())).withContext(ConversationContextRef.none());
    }

    private static Instant expiry(Instant now) {
        return now.plusSeconds(ConversationLimits.CONTEXT_TTL_SECONDS);
    }
}
