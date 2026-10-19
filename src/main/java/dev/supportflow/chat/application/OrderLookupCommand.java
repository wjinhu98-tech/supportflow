package dev.supportflow.chat.application;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只解析“查订单 商铺ID 订单号”；普通聊天返回空，格式错误则明确拒绝。
 * 本次不实现自然语言理解，也不从消息中提取买家身份。
 */
record OrderLookupCommand(long shopId, String orderNo) {

    private static final String PREFIX = "查订单";
    private static final int MAX_ORDER_NO_LENGTH = 64;
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "\\A查订单\\s+([0-9]+)\\s+(\\S+)\\z", Pattern.UNICODE_CHARACTER_CLASS);

    OrderLookupCommand {
        if (shopId <= 0 || orderNo == null || orderNo.isBlank()
                || orderNo.length() > MAX_ORDER_NO_LENGTH) {
            throw new InvalidChatCommandException();
        }
    }

    static Optional<OrderLookupCommand> parse(String message) {
        Objects.requireNonNull(message, "message must not be null");
        String normalized = message.strip();
        if (!normalized.startsWith(PREFIX)) {
            return Optional.empty();
        }

        Matcher matcher = COMMAND_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            throw new InvalidChatCommandException();
        }
        try {
            return Optional.of(new OrderLookupCommand(
                    Long.parseLong(matcher.group(1)), matcher.group(2)));
        } catch (NumberFormatException exception) {
            throw new InvalidChatCommandException();
        }
    }
}
