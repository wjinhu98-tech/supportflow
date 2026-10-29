package dev.supportflow.merchant.knowledge;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** Bounded in-memory text decoding. Markdown/HTML/URLs remain literal text, never executed or fetched. */
@Component
public class KnowledgeImportFileParser {
    public static final int MAX_FILE_BYTES = 64 * 1024;
    static final String NORMALIZATION_VERSION = "UTF8_TEXT_V1";
    private static final Set<String> TYPES = Set.of("text/plain", "text/markdown", "text/x-markdown", "application/octet-stream");

    Parsed parse(MultipartFile file) {
        if (file == null) throw KnowledgeImportException.fields();
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 255 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.codePoints().anyMatch(Character::isISOControl)) throw KnowledgeImportException.unsupported();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || !Set.of("txt", "md", "markdown").contains(name.substring(dot + 1).toLowerCase(Locale.ROOT)))
            throw KnowledgeImportException.unsupported();
        String type = file.getContentType();
        if (type != null && !type.isBlank() && !TYPES.contains(type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT)))
            throw KnowledgeImportException.unsupported();
        if (file.getSize() > MAX_FILE_BYTES) throw KnowledgeImportException.tooLarge();
        byte[] bytes;
        try (var input = file.getInputStream()) { bytes = input.readNBytes(MAX_FILE_BYTES + 1); }
        catch (IOException failure) { throw KnowledgeImportException.unreadable(); }
        if (bytes.length > MAX_FILE_BYTES) throw KnowledgeImportException.tooLarge();
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) { throw KnowledgeImportException.encoding(); }
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        content = content.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (content.isBlank() || Normalizer.normalize(content, Normalizer.Form.NFKC).isBlank()
                || content.length() > KnowledgeLimits.CONTENT || content.codePoints().anyMatch(point ->
                Character.isISOControl(point) && point != '\n' && point != '\t')) throw KnowledgeImportException.content();
        String title = name.substring(0, dot).strip();
        if (title.isBlank() || Normalizer.normalize(title, Normalizer.Form.NFKC).isBlank()) title = "导入资料";
        if (title.length() > KnowledgeLimits.TITLE) {
            title = title.substring(0, title.offsetByCodePoints(0,
                    Math.min(KnowledgeLimits.TITLE, title.codePointCount(0, title.length()))));
            // The title contract uses UTF-16 length, matching the existing editor validation.
            if (title.length() > KnowledgeLimits.TITLE) title = title.substring(0, KnowledgeLimits.TITLE);
            if (Character.isHighSurrogate(title.charAt(title.length() - 1))) title = title.substring(0, title.length() - 1);
        }
        return new Parsed(title, content, hash(content), bytes.length);
    }

    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256 unavailable"); }
    }
    record Parsed(String title, String content, String hash, int byteCount) { }
}
