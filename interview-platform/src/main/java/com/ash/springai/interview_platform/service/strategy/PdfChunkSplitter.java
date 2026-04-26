package com.ash.springai.interview_platform.service.strategy;

import org.springframework.stereotype.Component;

import com.ash.springai.interview_platform.service.chunking.StructuredChunkCandidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class PdfChunkSplitter {

    private static final Pattern PARA_BREAK = Pattern.compile("\\R{2,}");

    public List<StructuredChunkCandidate> splitStructured(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String normalized = content.replaceAll("\\R", "\n").trim();
        String[] paras = PARA_BREAK.split(normalized);
        List<StructuredChunkCandidate> merged = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String p : paras) {
            String s = p.replace('\n', ' ').trim();
            if (s.isEmpty()) {
                continue;
            }
            if (buf.length() + s.length() + 2 > 2000 && !buf.isEmpty()) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("source_type", "pdf");
                merged.add(new StructuredChunkCandidate("", "", buf.toString(), meta));
                buf.setLength(0);
            }
            if (!buf.isEmpty()) {
                buf.append("\n\n");
            }
            buf.append(s);
        }
        if (!buf.isEmpty()) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("source_type", "pdf");
            merged.add(new StructuredChunkCandidate("", "", buf.toString(), meta));
        }
        if (merged.isEmpty()) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("source_type", "pdf");
            merged.add(new StructuredChunkCandidate("", "", normalized, meta));
        }
        return merged;
    }
}
