package com.ash.springai.interview_platform.service.strategy;

import org.springframework.stereotype.Component;

import com.ash.springai.interview_platform.service.chunking.StructuredChunkCandidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ExcelChunkSplitter {

    public List<StructuredChunkCandidate> splitStructured(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String[] lines = content.split("\\R");
        List<StructuredChunkCandidate> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int rowStart = 1;
        int rowEnd = 0;
        for (String line : lines) {
            rowEnd++;
            if (buf.length() + line.length() + 1 > 1800 && !buf.isEmpty()) {
                flushChunk(out, buf.toString(), "Sheet1", rowStart, rowEnd - 1);
                String tail = overlapTail(buf.toString(), 250);
                buf.setLength(0);
                buf.append(tail);
                if (!tail.isEmpty()) {
                    buf.append('\n');
                }
                buf.append(line);
                rowStart = rowEnd;
            } else {
                if (!buf.isEmpty()) {
                    buf.append('\n');
                }
                buf.append(line);
            }
        }
        if (!buf.isEmpty()) {
            flushChunk(out, buf.toString(), "Sheet1", rowStart, rowEnd);
        }
        return out;
    }

    private static void flushChunk(List<StructuredChunkCandidate> out, String text, String sheet, int r0, int r1) {
        String t = text.trim();
        if (t.isEmpty()) {
            return;
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("sheet_name", sheet);
        meta.put("row_range", r0 + "-" + r1);
        meta.put("primary_columns", List.of());
        meta.put("record_id", "row-" + r0 + "-" + r1);
        meta.put("record_link_id", "row-" + r0 + "-" + r1);
        meta.put("source_type", "excel");
        out.add(new StructuredChunkCandidate("", "", t, meta));
    }

    private static String overlapTail(String block, int overlapChars) {
        if (overlapChars <= 0 || block.length() <= overlapChars) {
            return "";
        }
        return block.substring(block.length() - overlapChars).trim();
    }
}
