package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import com.ash.springai.interview_platform.Entity.IngestChunkDTO;
import com.ash.springai.interview_platform.config.IngestProperties;
import com.ash.springai.interview_platform.enums.DocumentType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChunkEnrichService {

    private static final Pattern ENTITY_EN = Pattern.compile("\\b[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*\\b");
    private static final Pattern WORD_LIKE = Pattern.compile("[\\p{L}\\p{N}]{3,}");

    private final IngestProperties ingestProperties;

    public ChunkEnrichService(IngestProperties ingestProperties) {
        this.ingestProperties = ingestProperties;
    }

    public IngestChunkDTO enrich(IngestChunkDTO chunk, DocumentType type, String kbId, String docId) {
        Map<String, Object> meta = new HashMap<>();
        if (chunk.metadata() != null) {
            meta.putAll(chunk.metadata());
        }
        String content = chunk.content() == null ? "" : chunk.content();
        meta.put("kb_id", kbId);
        meta.put("doc_id", docId);
        meta.put("doc_type", type.name());
        meta.put("chunk_id", kbId + "-" + chunk.chunkIndex());
        meta.put("ingest_version", ingestProperties.getVersion());
        meta.put("content_hash", DigestUtils.md5DigestAsHex(content.getBytes(StandardCharsets.UTF_8)));
        meta.put("keywords", extractKeywords(content));
        meta.put("entities", extractEntities(content));
        meta.put("quality_flags", evaluateQualityFlags(content));
        meta.put("importance_score", Math.min(1.0, chunk.tokenEstimate() / 500.0));
        if (type == DocumentType.EXCEL_TABLE) {
            meta.putIfAbsent("header_map", Map.of());
            meta.putIfAbsent("numeric_signals", List.of());
        }
        return new IngestChunkDTO(chunk.chunkIndex(), chunk.content(), chunk.tokenEstimate(), meta);
    }

    private static List<String> extractKeywords(String content) {
        Matcher m = WORD_LIKE.matcher(content.toLowerCase());
        Set<String> seen = new LinkedHashSet<>();
        while (m.find() && seen.size() < 12) {
            String w = m.group();
            if (w.length() >= 3) {
                seen.add(w);
            }
        }
        return new ArrayList<>(seen);
    }

    private static List<String> extractEntities(String content) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = ENTITY_EN.matcher(content);
        while (m.find() && out.size() < 8) {
            out.add(m.group());
        }
        return new ArrayList<>(out);
    }

    private static List<String> evaluateQualityFlags(String content) {
        List<String> flags = new ArrayList<>();
        if (content == null || content.isBlank()) {
            flags.add("empty");
        }
        if (content != null && content.length() < 20) {
            flags.add("short");
        }
        if (content != null && content.length() > 8000) {
            flags.add("long");
        }
        return flags;
    }
}
