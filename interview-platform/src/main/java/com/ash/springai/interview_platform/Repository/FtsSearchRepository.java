package com.ash.springai.interview_platform.Repository;

import com.ash.springai.interview_platform.Entity.HybridHitDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class FtsSearchRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<HybridHitDTO> search(String question, List<Long> knowledgeBaseIds, int topK, double minRank) {
        List<HybridHitDTO> hits = searchByWebsearch(question, knowledgeBaseIds, topK, minRank);
        if (!hits.isEmpty()) {
            return hits;
        }
        hits = searchByPlain(question, knowledgeBaseIds, topK, minRank);
        if (!hits.isEmpty()) {
            return hits;
        }
        return searchByILike(question, knowledgeBaseIds, topK);
    }

    private List<HybridHitDTO> searchByWebsearch(String question, List<Long> kbIds, int topK, double minRank) {
        String sql = """
            SELECT id::text AS chunk_id,
                   COALESCE(NULLIF(metadata->>'kb_id','')::bigint, 0) AS kb_id,
                   ts_rank_cd(to_tsvector('simple', content), websearch_to_tsquery('simple', ?)) AS rank,
                   ts_headline('simple', content, websearch_to_tsquery('simple', ?)) AS highlight
            FROM vector_store
            WHERE to_tsvector('simple', content) @@ websearch_to_tsquery('simple', ?)
              AND ts_rank_cd(to_tsvector('simple', content), websearch_to_tsquery('simple', ?)) >= ?
              AND (%s)
            ORDER BY rank DESC, id DESC
            LIMIT ?
            """.formatted(buildKbFilter(kbIds));
        return queryHits(sql, question, question, question, question, minRank, Math.max(topK, 1), kbIds);
    }

    private List<HybridHitDTO> searchByPlain(String question, List<Long> kbIds, int topK, double minRank) {
        String sql = """
            SELECT id::text AS chunk_id,
                   COALESCE(NULLIF(metadata->>'kb_id','')::bigint, 0) AS kb_id,
                   ts_rank_cd(to_tsvector('simple', content), plainto_tsquery('simple', ?)) AS rank,
                   ts_headline('simple', content, plainto_tsquery('simple', ?)) AS highlight
            FROM vector_store
            WHERE to_tsvector('simple', content) @@ plainto_tsquery('simple', ?)
              AND ts_rank_cd(to_tsvector('simple', content), plainto_tsquery('simple', ?)) >= ?
              AND (%s)
            ORDER BY rank DESC, id DESC
            LIMIT ?
            """.formatted(buildKbFilter(kbIds));
        return queryHits(sql, question, question, question, question, minRank, Math.max(topK, 1), kbIds);
    }

    private List<HybridHitDTO> searchByILike(String question, List<Long> kbIds, int topK) {
        String sql = """
            SELECT id::text AS chunk_id,
                   COALESCE(NULLIF(metadata->>'kb_id','')::bigint, 0) AS kb_id,
                   content AS highlight
            FROM vector_store
            WHERE content ILIKE ? ESCAPE '\\' AND (%s)
            ORDER BY id DESC
            LIMIT ?
            """.formatted(buildKbFilter(kbIds));
        String like = "%" + escapeLike(question) + "%";
        Object[] params = mergeKbParams(new Object[]{like, Math.max(topK, 1)}, kbIds);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new HybridHitDTO(
            rs.getString("chunk_id"),
            rs.getLong("kb_id"),
            "fts",
            0.20,
            truncate(rs.getString("highlight")),
            "ILIKE补召回命中"
        ), params);
    }

    private List<HybridHitDTO> queryHits(String sql, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, List<Long> kbIds) {
        Object[] params = mergeKbParams(new Object[]{p1, p2, p3, p4, p5, p6}, kbIds);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new HybridHitDTO(
            rs.getString("chunk_id"),
            rs.getLong("kb_id"),
            "fts",
            rs.getDouble("rank"),
            truncate(rs.getString("highlight")),
            "FTS相关性命中"
        ), params);
    }

    private String buildKbFilter(List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return "1=1";
        }
        String placeholders = kbIds.stream().map(id -> "?").collect(Collectors.joining(","));
        return "COALESCE(NULLIF(metadata->>'kb_id','')::bigint, 0) IN (" + placeholders + ")";
    }

    private Object[] mergeKbParams(Object[] first, List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return first;
        }
        List<Object> merged = new ArrayList<>(List.of(first));
        merged.addAll(kbIds);
        return merged.toArray();
    }

    private String escapeLike(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 240 ? text.substring(0, 240) + "..." : text;
    }
}
