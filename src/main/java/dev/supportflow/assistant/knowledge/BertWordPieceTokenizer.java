package dev.supportflow.assistant.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Case-preserving BERT basic + greedy WordPiece tokenization for the pinned Chinese BGE vocabulary. */
final class BertWordPieceTokenizer {
    static final int MAX_TOKENS = 384;
    private final Map<String, Integer> vocabulary = new HashMap<>();
    private final int unknown;
    private final int cls;
    private final int sep;

    BertWordPieceTokenizer(List<String> words) {
        for (int index = 0; index < words.size(); index++) {
            if (vocabulary.put(words.get(index), index) != null) throw new IllegalArgumentException("Duplicate vocabulary token");
        }
        unknown = required("[UNK]"); cls = required("[CLS]"); sep = required("[SEP]");
        required("[PAD]");
    }

    long[] encode(String text) {
        if (text == null || text.isBlank() || text.length() > 2000) throw new IllegalArgumentException("Invalid embedding text");
        var basic = new StringBuilder();
        text.codePoints().forEach(point -> {
            int type = Character.getType(point);
            if (point == 0 || point == 0xfffd || ((type == Character.CONTROL || type == Character.FORMAT)
                    && point != '\t' && point != '\n' && point != '\r')) return;
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) basic.append(' ');
            else if (Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN || punctuation(point))
                basic.append(' ').appendCodePoint(point).append(' ');
            else basic.appendCodePoint(point);
        });
        var result = new ArrayList<Long>();
        result.add((long) cls);
        for (String word : basic.toString().strip().split("\\s+")) {
            if (word.isEmpty()) continue;
            var pieces = new ArrayList<Long>();
            int[] points = word.codePoints().toArray();
            if (points.length > 100) pieces.add((long) unknown);
            else for (int start = 0; start < points.length;) {
                Integer token = null;
                int end = points.length;
                for (; end > start; end--) {
                    String candidate = (start == 0 ? "" : "##") + new String(points, start, end - start);
                    if ((token = vocabulary.get(candidate)) != null) break;
                }
                if (token == null) { pieces.clear(); pieces.add((long) unknown); break; }
                pieces.add(token.longValue()); start = end;
            }
            for (long token : pieces) {
                if (result.size() == MAX_TOKENS - 1) break;
                result.add(token);
            }
            if (result.size() == MAX_TOKENS - 1) break;
        }
        result.add((long) sep);
        return result.stream().mapToLong(Long::longValue).toArray();
    }

    private int required(String token) {
        Integer value = vocabulary.get(token);
        if (value == null) throw new IllegalArgumentException("Missing BERT special token");
        return value;
    }

    private static boolean punctuation(int point) {
        int type = Character.getType(point);
        return point >= 33 && point <= 47 || point >= 58 && point <= 64 || point >= 91 && point <= 96
                || point >= 123 && point <= 126 || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION;
    }
}
