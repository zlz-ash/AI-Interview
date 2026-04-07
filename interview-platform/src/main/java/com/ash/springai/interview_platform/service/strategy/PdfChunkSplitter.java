package com.ash.springai.interview_platform.service.strategy;

import org.springframework.stereotype.Component;

import com.ash.springai.interview_platform.Entity.IngestChunkDTO;
import com.ash.springai.interview_platform.config.IngestProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class PdfChunkSplitter {

    private static final Pattern PARA_BREAK = Pattern.compile("\\R{2,}");

    private final IngestProperties ingestProperties;

    public PdfChunkSplitter(IngestProperties ingestProperties) {
        this.ingestProperties = ingestProperties;
    }

    public List<IngestChunkDTO> split(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        IngestProperties.Pdf cfg = ingestProperties.getPdf();
        int maxTok = cfg.getTargetMaxTokens();
        int overlapTok = cfg.getOverlapMaxTokens();
        int maxChars = Math.max(1, maxTok * 4);
        int overlapChars = Math.min(maxChars - 1, Math.max(0, overlapTok * 4));

        String normalized = content.replaceAll("\\R", "\n").trim();
        String[] paras = PARA_BREAK.split(normalized);
        List<String> merged = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String p : paras) {
            String s = p.replace('\n', ' ').trim();
            if (s.isEmpty()) {
                continue;
            }
            if (buf.length() + s.length() + 2 > maxChars && !buf.isEmpty()) {
                merged.add(buf.toString());
                buf.setLength(0);
            }
            if (!buf.isEmpty()) {
                buf.append("\n\n");
            }
            buf.append(s);
        }
        if (!buf.isEmpty()) {
            merged.add(buf.toString());
        }
        if (merged.isEmpty()) {
            merged.add(normalized);
        }

        List<IngestChunkDTO> out = new ArrayList<>();
        int idx = 0;
        for (String block : merged) {
            for (String piece : splitBySize(block, maxChars, overlapChars)) {
                String t = piece.trim();
                if (t.isEmpty()) {
                    continue;
                }
                Map<String, Object> meta = new HashMap<>();
                meta.put("section_path", "");
                meta.put("chunk_index", idx + 1);
                out.add(new IngestChunkDTO(++idx, t, MarkdownChunkSplitter.estimateTokens(t), meta));
            }
        }
        return out;
    }

    private static List<String> splitBySize(String text, int maxChars, int overlapChars) {
        List<String> parts = new ArrayList<>();
        if (text.length() <= maxChars) {
            parts.add(text);
            return parts;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            parts.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            int next = end - overlapChars;
            start = Math.max(0, next);
            if (start >= end) {
                start = end;
            }
        }
        return parts;
    }
}
