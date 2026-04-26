package com.ash.springai.interview_platform.Repository;

import com.ash.springai.interview_platform.Entity.ChunkItemDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Repository
@RequiredArgsConstructor
public class VectorChunkBrowseRepository {

    private static final Pattern TOKEN_COUNT_PATTERN = Pattern.compile("\"token_count\"\\s*:\\s*(\\d+)");
    private final JdbcTemplate jdbcTemplate;

    public List<ChunkItemDTO> findChunksByKnowledgeBaseId(Long knowledgeBaseId, int offset, int limit) {
        String sql = """
            SELECT id::text AS chunk_id,
                   row_number() OVER (ORDER BY id) AS chunk_index,
                   content,
                   metadata::text AS metadata
            FROM vector_store
            WHERE metadata->>'kb_id' = ?
            ORDER BY id
            OFFSET ? LIMIT ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapChunk(rs.getString("chunk_id"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                rs.getString("metadata")),
            knowledgeBaseId.toString(), offset, limit);
    }

    public Optional<ChunkItemDTO> findChunkDetailById(Long knowledgeBaseId, String chunkId) {
        String sql = """
            SELECT id::text AS chunk_id,
                   content,
                   metadata::text AS metadata
            FROM vector_store
            WHERE id::text = ? AND metadata->>'kb_id' = ?
            """;
        List<ChunkItemDTO> rows = jdbcTemplate.query(sql, (rs, rowNum) -> mapChunk(
                rs.getString("chunk_id"),
                0,
                rs.getString("content"),
                rs.getString("metadata")),
            chunkId, knowledgeBaseId.toString());
        return rows.stream().findFirst();
    }

    public long countByKnowledgeBaseId(Long knowledgeBaseId) {
        String sql = "SELECT COUNT(1) FROM vector_store WHERE metadata->>'kb_id' = ?";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, knowledgeBaseId.toString());
        return count == null ? 0 : count;
    }

    public ChunkStats fetchStats(Long knowledgeBaseId) {
        String sql = """
            SELECT COALESCE(AVG(char_length(content)), 0) AS avg_len,
                   COALESCE(MIN(char_length(content)), 0) AS min_len,
                   COALESCE(MAX(char_length(content)), 0) AS max_len
            FROM vector_store
            WHERE metadata->>'kb_id' = ?
            """;
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) ->
                new ChunkStats(rs.getDouble("avg_len"), rs.getInt("min_len"), rs.getInt("max_len")),
            knowledgeBaseId.toString());
    }

    private ChunkItemDTO mapChunk(String chunkId, int chunkIndex, String content, String metadata) {
        String safeContent = content == null ? "" : content;
        String preview = safeContent.length() > 160 ? safeContent.substring(0, 160) + "..." : safeContent;
        int length = safeContent.length();
        Integer tokenEstimate = extractTokenCount(metadata);
        return new ChunkItemDTO(chunkId, chunkIndex, preview, safeContent, length, tokenEstimate, metadata);
    }

    private static Integer extractTokenCount(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        Matcher matcher = TOKEN_COUNT_PATTERN.matcher(metadata);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    public record ChunkStats(double avgChunkLength, int minChunkLength, int maxChunkLength) {}
}
