package dev.supportflow.assistant.planning;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared exact SF-number evidence; neither a model nor the offline planner may ignore ambiguity. */
public final class ExplicitOrderNumbers {
    private static final Pattern ORDER_NUMBER =
            Pattern.compile("(?<![A-Za-z0-9_.-])SF[A-Za-z0-9_-]+(?![A-Za-z0-9_.-])");

    private ExplicitOrderNumbers() { }

    public static Set<String> extract(String message) {
        Objects.requireNonNull(message, "message must not be null");
        Set<String> explicit = new LinkedHashSet<>();
        Matcher matcher = ORDER_NUMBER.matcher(message);
        while (matcher.find()) {
            explicit.add(matcher.group());
        }
        return Set.copyOf(explicit);
    }
}
