package dev.supportflow.chat.application;

import dev.supportflow.chat.application.model.OrderSelectionIntent;
import dev.supportflow.order.domain.model.ProductOrderKeyword;

import java.text.Normalizer;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative literal-name resolution for read questions; it neither invents product aliases nor authorizes writes. */
public record ProductOrderQuestion(String keyword, OrderSelectionIntent intent) {
    private static final Pattern READ_SUBJECT = Pattern.compile(
            "发货|发出|寄出|出库|发没发|发了|物流|快递|配送进度|到哪里|到哪|到货|送达|送到|什么时候(?:能)?到|(?:什么|啥)?状态");
    private static final Pattern LOGISTICS = Pattern.compile("物流|快递|配送|到哪|到货|送达|送到|什么时候(?:能)?到");
    private static final Pattern PUBLIC_SHIPPING = Pattern.compile("一般|通常|购买前|下单后多久|发货安排|发货时效|发货时间说明");
    private static final Pattern GENERIC_SUFFIX = Pattern.compile("^(?:信息|情况|进度|状态|记录|轨迹|详情|时间|什么时候|何时|多久|到哪|怎么样|怎么了|是否|能否|说明|查询)$");
    private static final Pattern PUBLIC_OR_WRITE = Pattern.compile(
            "政策|规则|一般|通常|购买前|下单后多久|保养|清洗|怎么用|如何使用|多少钱|价格|运费|"
                    + "退款|退货|售后|申请|提交|取消|修改|付款|支付|审批|确认收货|忽略|绕过|授权|密钥|密码|"
                    + "system\\s*:|assistant\\s*:|ignore|refund|approve|delete|update", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIX = Pattern.compile(
            "^(?:我想问一下|我想问|我想知道|想知道|麻烦帮我|请帮我|帮我|给我|麻烦|请问|请|问一下|问下|"
                    + "查询一下|查询|查一下|查下|查看一下|查看|查查|看看|查|我之前买的|我购买的|我下单的|我订的|我买的|"
                    + "我之前购买的|刚买的|之前买的|我的|我那|我这|那副|这副|那件|这件|那双|这双|那只|这只|"
                    + "那款|这款|那个|这个|那套|这套|一副|一件|一个|我|的)");
    private static final Pattern TAIL = Pattern.compile(
            "(?:什么时候|啥时候|现在|今天|到底|已经|是否|有没有|还没有|还没|怎么|为什么|是|什么|啥|能|可以|还|没|的)$");
    private static final Pattern PUNCTUATION = Pattern.compile("^[\\s:：,，。？?！!]+|[\\s:：,，。？?！!]+$");
    private static final Set<String> GENERIC = Set.of("订单", "我的订单", "这单", "那单", "这笔订单", "那笔订单", "商品", "东西", "包裹", "快递", "它", "那", "这");

    public static Optional<ProductOrderQuestion> parse(String input) {
        String message = Normalizer.normalize(input.strip(), Normalizer.Form.NFKC);
        if (PUBLIC_OR_WRITE.matcher(message).find()) return Optional.empty();
        var read = READ_SUBJECT.matcher(message);
        if (!read.find()) return Optional.empty();
        String prefix = clean(message.substring(0, read.start()));
        String keyword = strip(prefix);
        if (keyword.isEmpty()) {
            String suffix = message.substring(read.end()).replaceFirst("^(?:到了没|到了吗|到哪里了|到哪了|怎么样|如何|情况|进度|了吗|了没|没|吗|了|呢)+", "");
            keyword = strip(clean(suffix));
            if (READ_SUBJECT.matcher(keyword).find() || GENERIC_SUFFIX.matcher(keyword).matches()) return Optional.empty();
        }
        if (keyword.isEmpty() || GENERIC.contains(keyword)) return Optional.empty();
        return Optional.of(new ProductOrderQuestion(keyword, LOGISTICS.matcher(message).find()
                ? OrderSelectionIntent.LOGISTICS : OrderSelectionIntent.ORDER_STATUS));
    }

    public static boolean isPublicShippingQuestion(String input) {
        return PUBLIC_SHIPPING.matcher(input).find() && READ_SUBJECT.matcher(input).find();
    }

    public boolean validKeyword() {
        try { new ProductOrderKeyword(keyword); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static String clean(String value) { return PUNCTUATION.matcher(value.strip()).replaceAll("").strip(); }
    private static String strip(String value) {
        String current = value;
        for (int index = 0; index < 24; index++) {
            String next = clean(TAIL.matcher(PREFIX.matcher(current).replaceFirst("")).replaceFirst(""));
            if (next.equals(current)) return next;
            current = next;
        }
        return current;
    }
}
