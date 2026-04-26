package com.ash.springai.interview_platform.service.chunking;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.IntArrayList;
import com.knuddels.jtokkit.api.EncodingType;

import java.util.Locale;

public class JtokkitTokenCounter implements TokenCounter {

    private final Encoding encoding;

    public JtokkitTokenCounter(String encodingName) {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        String normalized = encodingName == null ? "cl100k_base" : encodingName.trim();
        EncodingType type = EncodingType.valueOf(normalized.toUpperCase(Locale.ROOT));
        this.encoding = registry.getEncoding(type);
    }

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    @Override
    public String truncateToTokens(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        IntArrayList tokens = encoding.encode(text);
        if (tokens.size() <= maxTokens) {
            return text;
        }
        IntArrayList prefix = new IntArrayList(maxTokens);
        for (int i = 0; i < maxTokens; i++) {
            prefix.add(tokens.get(i));
        }
        return encoding.decode(prefix);
    }
}
