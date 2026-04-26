package com.ash.springai.interview_platform.service.strategy;

import org.springframework.stereotype.Component;

import com.ash.springai.interview_platform.service.chunking.StructuredChunkCandidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarkdownChunkSplitter {

    private static final Pattern HEADING_START = Pattern.compile("(?m)^(#{1,6})\\s+(.+)$");

    public List<StructuredChunkCandidate> splitStructured(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<StructuredChunkCandidate> out = new ArrayList<>();
        for (Section sec : splitIntoSections(content.trim())) {
            String text = sec.body() == null ? "" : sec.body().trim();
            if (text.isEmpty()) {
                continue;
            }
            Map<String, Object> meta = new HashMap<>();
            meta.put("source_type", "markdown");
            out.add(new StructuredChunkCandidate(sec.path(), sec.heading(), text, meta));
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

    private record Section(String path, String heading, String body) {}
}
