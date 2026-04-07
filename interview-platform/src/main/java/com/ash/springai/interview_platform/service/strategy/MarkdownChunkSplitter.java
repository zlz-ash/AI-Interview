package com.ash.springai.interview_platform.service.strategy;

import org.springframework.stereotype.Component;

import com.ash.springai.interview_platform.Entity.IngestChunkDTO;
import com.ash.springai.interview_platform.config.IngestProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarkdownChunkSplitter {

    private static final Pattern HEADING_START = Pattern.compile("(?m)^(#{1,6})\\s+(.+)$");

    private final IngestProperties ingestProperties;

    public MarkdownChunkSplitter(IngestProperties ingestProperties) {
        this.ingestProperties = ingestProperties;
    }

    public List<IngestChunkDTO> split(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        IngestProperties.Markdown cfg = ingestProperties.getMarkdown();
        int maxTok = cfg.getTargetMaxTokens();
        int overlapTok = cfg.getOverlapMaxTokens();
        int maxChars = Math.max(1, maxTok * 4);
        int overlapChars = Math.min(maxChars - 1, Math.max(0, overlapTok * 4));

        List<IngestChunkDTO> out = new ArrayList<>();
        int globalIdx = 0;
        for (Section sec : splitIntoSections(content.trim())) {
            for (String piece : splitBySize(sec.body(), maxChars, overlapChars)) {
                String text = piece.trim();
                if (text.isEmpty()) {
                    continue;
                }
                int est = estimateTokens(text);
                Map<String, Object> meta = new HashMap<>();
                meta.put("section_path", sec.path());
                meta.put("heading", sec.heading());
                meta.put("chunk_index", globalIdx + 1);
                out.add(new IngestChunkDTO(++globalIdx, text, est, meta));
            }
        }
        return out;
    }

    private List<Section> splitIntoSections(String content) {
        String[] blocks = content.split("(?m)(?=^#{1,6}\\s+)");
        List<Section> sections = new ArrayList<>();
        String path = "";
        for (String block : blocks) {
            if (block.isBlank()) {
                continue;
            }
            Matcher hm = HEADING_START.matcher(block);
            String heading;
            String body;
            if (hm.find() && hm.start() == 0) {
                int level = hm.group(1).length();
                heading = hm.group(2).trim();
                path = updatePath(path, level, heading);
                body = block.substring(hm.end()).trim();
                if (body.isEmpty()) {
                    sections.add(new Section(path, heading, ""));
                    continue;
                }
            } else {
                heading = "";
                body = block.trim();
            }
            sections.add(new Section(path, heading, body));
        }
        if (sections.isEmpty()) {
            sections.add(new Section("", "", content));
        }
        return sections;
    }

    private static String updatePath(String previousPath, int level, String heading) {
        if (previousPath == null || previousPath.isEmpty()) {
            return heading;
        }
        String[] parts = previousPath.split(" > ");
        if (level <= parts.length) {
            String[] next = new String[level];
            System.arraycopy(parts, 0, next, 0, level - 1);
            next[level - 1] = heading;
            return String.join(" > ", next);
        }
        return previousPath + " > " + heading;
    }

    private static List<String> splitBySize(String text, int maxChars, int overlapChars) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return parts;
        }
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

    static int estimateTokens(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return (s.length() + 3) / 4;
    }

    private record Section(String path, String heading, String body) {}
}
