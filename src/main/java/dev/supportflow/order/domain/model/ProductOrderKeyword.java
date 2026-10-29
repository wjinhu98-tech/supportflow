package dev.supportflow.order.domain.model;

import dev.supportflow.order.domain.OrderQueryLimits;

import java.util.ArrayList;
import java.util.List;

/** Shared bounded query value for chat parsing, application validation and literal database search. */
public record ProductOrderKeyword(String value) {
    private static final List<String> COLOURS = List.of("米白色", "深蓝色", "浅蓝色", "黑色", "白色", "蓝色", "红色",
            "银色", "粉色", "灰色", "绿色", "棕色", "紫色", "黄色");

    public ProductOrderKeyword {
        if (value == null || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Product keyword cannot contain control characters");
        value = value.strip();
        if (value.length() > OrderQueryLimits.MAX_PRODUCT_KEYWORD_LENGTH)
            throw new IllegalArgumentException("Product keyword is too long");
        value = value.replaceAll("(?U)\\s+", " ").strip();
        if (value.length() < 2) throw new IllegalArgumentException("Product keyword must contain at least two characters");
        splitTerms(value);
    }

    /** Derived only from the validated value, so callers cannot supply a different token interpretation. */
    public List<String> terms() { return splitTerms(value); }

    private static List<String> splitTerms(String keyword) {
        var result = new ArrayList<String>();
        for (String word : keyword.split(" ")) {
            String colour = COLOURS.stream().filter(word::startsWith)
                    .filter(prefix -> word.length() - prefix.length() >= 2).findFirst().orElse(null);
            if (colour == null) result.add(word);
            else { result.add(colour); result.add(word.substring(colour.length())); }
            if (result.size() > OrderQueryLimits.MAX_PRODUCT_SEARCH_TERMS)
                throw new IllegalArgumentException("Use at most four bounded product search terms");
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Product keyword has no search terms");
        return List.copyOf(result);
    }
}
