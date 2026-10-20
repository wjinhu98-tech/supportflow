package dev.supportflow.chat.application;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.supportflow.order.domain.OrderQueryLimits;

/**
 * 只解析“查订单 订单号”。商铺由HTTP入口决定，指令不携带商铺或买家身份。
 * 无分隔符的自然语言交给模型；单独“查订单”由服务展示可选订单。
 */
record OrderLookupCommand(String orderNo) {

    private static final String PREFIX = "查订单";
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "\\A查订单\\s+(\\S+)\\z", Pattern.UNICODE_CHARACTER_CLASS);

    OrderLookupCommand {
        if (orderNo == null || orderNo.isBlank() || orderNo.length() > OrderQueryLimits.MAX_ORDER_NO_LENGTH
                || orderNo.codePoints().anyMatch(codePoint ->
                        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                                || Character.isISOControl(codePoint))) {
            throw new InvalidChatCommandException();
        }
    }

    static Optional<OrderLookupCommand> parse(String message) {
        Objects.requireNonNull(message, "message must not be null");
        String normalized = message.strip();
        if (!normalized.startsWith(PREFIX) || normalized.equals(PREFIX)
                || !(Character.isWhitespace(normalized.codePointAt(PREFIX.length()))
                || Character.isSpaceChar(normalized.codePointAt(PREFIX.length())))) {
            return Optional.empty();
        }

        Matcher matcher = COMMAND_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            throw new InvalidChatCommandException();
        }
        return Optional.of(new OrderLookupCommand(matcher.group(1)));
    }
}
