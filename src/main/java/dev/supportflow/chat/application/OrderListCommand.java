package dev.supportflow.chat.application;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * “我的订单”从首批开始；“我的订单 游标”从指定位置继续。
 * 指令不携带买家身份，也不允许客户端改变每批大小。
 */
record OrderListCommand(long afterId) {

    private static final String PREFIX = "我的订单";
    private static final String USAGE =
            "列表格式：我的订单，或：我的订单 <非负整数游标>；翻页请复制回复中的指令";
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "\\A我的订单(?:\\s+([0-9]+))?\\z", Pattern.UNICODE_CHARACTER_CLASS);

    OrderListCommand {
        if (afterId < 0) {
            throw new InvalidChatCommandException(USAGE);
        }
    }

    static Optional<OrderListCommand> parse(String message) {
        Objects.requireNonNull(message, "message must not be null");
        String normalized = message.strip();
        if (!normalized.startsWith(PREFIX)
                || normalized.length() > PREFIX.length()
                && !(Character.isWhitespace(normalized.codePointAt(PREFIX.length()))
                || Character.isSpaceChar(normalized.codePointAt(PREFIX.length())))) {
            return Optional.empty();
        }

        Matcher matcher = COMMAND_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            throw new InvalidChatCommandException(USAGE);
        }
        String cursor = matcher.group(1);
        try {
            return Optional.of(new OrderListCommand(cursor == null ? 0L : Long.parseLong(cursor)));
        } catch (NumberFormatException exception) {
            throw new InvalidChatCommandException(USAGE);
        }
    }
}
