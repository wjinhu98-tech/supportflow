package dev.supportflow.chat.application;

import java.util.regex.Pattern;

/** Conservative exclusions for a shop assistant; unknown business questions still use their normal path. */
public final class CustomerQuestionScope {
    private static final Pattern BUSINESS_SUBJECT = Pattern.compile(
            "订单|查单|下单|购买|付款|支付|收货|发货|物流|快递|运单|配送|运费|退款|退货|换货|售后|"
                    + "审批|保修|维修|客服|店铺|商铺|商家|商品|产品|库存|规格|型号|尺码|尺寸|颜色|材质|"
                    + "包装|礼盒|贺卡|赠品|优惠|价格|金额|总价|保养|洗涤|清洗|使用说明|"
                    + "编码|货号|条码|序列号|保质期|有效期|质保|折扣|发票|签收|漏发|少发|破损|损坏|色差|"
                    + "耳机|鼠标|键盘|电脑|收纳盒|四件套|马克杯|充电|知识库|政策|规则|"
                    + "\\border\\b|\\bshipping\\b|\\bdelivery\\b|\\brefund\\b|\\bproduct\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BUSINESS_OBJECT = Pattern.compile(
            "订单|查单|收货|发货|物流|快递|运单|配送|运费|退款|退货|换货|售后|"
                    + "店铺|商铺|商品|产品|包装|礼盒|贺卡|耳机|鼠标|键盘|电脑|收纳盒|四件套|马克杯|"
                    + "\\border\\b|\\bshipping\\b|\\bdelivery\\b|\\brefund\\b|\\bproduct\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BUSINESS_OPERATION = Pattern.compile(
            "^(?:(?:请|麻烦|帮我|我要|我想)\\h*)?(?:(?:申请|办理|提交)\\h*)?"
                    + "(?:售后|退款|退货|换货|确认收货)(?:\\h|[：:]|$)");
    private static final Pattern ANSWER_MECHANISM = Pattern.compile(
            "(?:回答|回复|答案).{0,12}(?:数据库|SQL|源代码|系统提示词|大模型|算法)"
                    + "|(?:通过|查询|查|根据|调用).{0,8}(?:数据库|SQL|大模型|算法).{0,12}(?:回答|回复|生成)"
                    + "|(?:你|系统|机器人).{0,12}(?:用什么|使用什么|是什么|用的|基于什么).{0,8}(?:模型|算法|框架)"
                    + "|(?:查看|展示|提供|告诉|给我看).{0,8}(?:你的|系统的|你们的)(?:源代码|系统提示词|提示词)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSWER_REFERENCE = Pattern.compile(
            "^(?:你)?(?:刚刚|刚才|上次|上一条|之前)(?:回答|回复|说)(?:的)?"
                    + "(?:问题|内容|答案|那句话)?(?:是什么|什么)?[。？?！!\\s]*$");
    private static final Pattern UNRELATED_SUBJECT = Pattern.compile(
            "天气|气温|星座|运势|股票|股价|彩票|足球比分|篮球比分|足球规则|篮球规则|世界杯|总统|首相");
    private static final String GENERATION_REQUEST =
            "^(?:请|麻烦|能否|能不能|可以|可不可以|怎么|如何)?\\h*"
                    + "(?:(?:帮我|给我|为我|替我)\\h*)?(?:(?:用|使用).{1,20})?"
                    + "(?:写|编写|生成|实现|开发|创作|翻译|解答|计算).{0,50}";
    private static final Pattern PROGRAMMING_REQUEST = Pattern.compile(
            GENERATION_REQUEST + "(?:代码|程序|脚本|算法|SQL)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATIVE_REQUEST = Pattern.compile(
            GENERATION_REQUEST + "(?:作文|诗歌|一首诗|小说|情书|数学题|微积分)",
            Pattern.CASE_INSENSITIVE);

    private CustomerQuestionScope() { }

    public static boolean hasBusinessSubject(String message) {
        return message != null && BUSINESS_SUBJECT.matcher(message).find();
    }

    public static boolean isClearlyUnrelated(String message) {
        if (message == null || message.isBlank()) return false;
        String text = message.strip();
        // Reasons are business input, even when a buyer happens to mention technical words in them.
        if (BUSINESS_OPERATION.matcher(text).find()) return false;
        if (ANSWER_MECHANISM.matcher(text).find() || ANSWER_REFERENCE.matcher(text).matches()) return true;
        if (PROGRAMMING_REQUEST.matcher(text).find()) return true;
        // A price or rule alone does not make an unrelated topic a shop question.
        // Concrete product or order subjects still protect legitimate capabilities and shipping FAQs.
        return !BUSINESS_OBJECT.matcher(text).find() && (UNRELATED_SUBJECT.matcher(text).find()
                || CREATIVE_REQUEST.matcher(text).find());
    }
}
