package com.ash.springai.interview_platform.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class VectorStoreChunkContentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 按 chunkId 顺序返回 Document；某 id 在库中不存在则跳过该条。
     */
    public List<Document> loadDocumentsInOrder(List<String> chunkIdsOrdered) {
        if (chunkIdsOrdered == null || chunkIdsOrdered.isEmpty()) {
            return List.of();
        }
        String inClause = String.join(",", Collections.nCopies(chunkIdsOrdered.size(), "?"));
        String sql = """
            SELECT id::text AS chunk_id, content, metadata::text AS metadata
            FROM vector_store
            WHERE id::text IN (""" + inClause + ")";
        Map<String, Document> byId = new LinkedHashMap<>();
        jdbcTemplate.query(sql, (ResultSet rs) -> {
            while (rs.next()) {
                String chunkId = rs.getString("chunk_id");
                String content = rs.getString("content");
                Map<String, Object> meta = parseMetadata(rs.getString("metadata"));
                byId.put(chunkId, new Document(content != null ? content : "", meta));
            }
            return null;
        }, chunkIdsOrdered.toArray());
        List<Document> out = new ArrayList<>();
        for (String id : chunkIdsOrdered) {
            Document d = byId.get(id);
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
