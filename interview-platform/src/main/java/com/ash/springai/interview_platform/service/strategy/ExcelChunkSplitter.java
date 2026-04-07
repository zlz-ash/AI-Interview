package com.ash.springai.interview_platform.service.strategy;

import org.springframework.stereotype.Component;

import com.ash.springai.interview_platform.Entity.IngestChunkDTO;
import com.ash.springai.interview_platform.config.IngestProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ExcelChunkSplitter {

    private final IngestProperties ingestProperties;

    public ExcelChunkSplitter(IngestProperties ingestProperties) {
        this.ingestProperties = ingestProperties;
    }

    public List<IngestChunkDTO> split(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        IngestProperties.Excel cfg = ingestProperties.getExcel();
        int maxTok = cfg.getTargetMaxTokens();
        int overlapTok = cfg.getOverlapMaxTokens();
        int maxChars = Math.max(1, maxTok * 4);
        int overlapChars = Math.min(maxChars - 1, Math.max(0, overlapTok * 4));

        String[] lines = content.split("\\R");
        List<IngestChunkDTO> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int rowStart = 1;
        int rowEnd = 0;
        int chunkIdx = 0;
        for (String line : lines) {
            rowEnd++;
            if (buf.length() + line.length() + 1 > maxChars && !buf.isEmpty()) {
                flushChunk(out, ++chunkIdx, buf.toString(), "Sheet1", rowStart, rowEnd - 1);
                String tail = overlapTail(buf.toString(), overlapChars);
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
            flushChunk(out, ++chunkIdx, buf.toString(), "Sheet1", rowStart, rowEnd);
        }
        return out;
    }

    private static void flushChunk(List<IngestChunkDTO> out, int index, String text, String sheet, int r0, int r1) {
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
        meta.put("chunk_index", index);
        out.add(new IngestChunkDTO(index, t, MarkdownChunkSplitter.estimateTokens(t), meta));
    }

    private static String overlapTail(String block, int overlapChars) {
        if (overlapChars <= 0 || block.length() <= overlapChars) {
            return "";
        }
        return block.substring(block.length() - overlapChars).trim();
    }
}
