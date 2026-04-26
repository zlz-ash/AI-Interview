package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import com.ash.springai.interview_platform.Repository.KnowledgeBaseRepository;
import com.ash.springai.interview_platform.Repository.VectorRepository;
import com.ash.springai.interview_platform.Entity.IngestChunkDTO;
import com.ash.springai.interview_platform.common.AsyncTaskStreamConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class KnowledgeBaseVectorService {

    private static final int MAX_BATCH_SIZE = AsyncTaskStreamConstants.BATCH_SIZE;
    private final VectorStore vectorStore;
    private final VectorRepository vectorRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public KnowledgeBaseVectorService(
        VectorStore vectorStore,
        VectorRepository vectorRepository,
        KnowledgeBaseRepository knowledgeBaseRepository
    ) {
        this.vectorStore = vectorStore;
        this.vectorRepository = vectorRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    @Transactional
    public void vectorizeAndStoreChunks(Long knowledgeBaseId, List<IngestChunkDTO> chunks) {
        log.info("开始向量化知识库(分块 DTO): kbId={}, chunks={}", knowledgeBaseId,
            chunks == null ? 0 : chunks.size());
        if (chunks == null || chunks.isEmpty()) {
            throw new RuntimeException("无可用文本块");
        }
        try {
            deleteByKnowledgeBaseId(knowledgeBaseId);

            List<Document> documents = new ArrayList<>();
            for (IngestChunkDTO c : chunks) {
                Map<String, Object> meta = new HashMap<>();
                if (c.metadata() != null) {
                    meta.putAll(normalizeMetadata(c.metadata()));
                }
                meta.putIfAbsent("kb_id", knowledgeBaseId.toString());
                documents.add(new Document(c.content(), meta));
            }

            int totalChunks = documents.size();
            int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;
            for (int i = 0; i < batchCount; i++) {
                int start = i * MAX_BATCH_SIZE;
                int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
                vectorStore.add(documents.subList(start, end));
            }

            knowledgeBaseRepository.findById(knowledgeBaseId).ifPresent(kb -> {
                kb.setChunkCount(totalChunks);
                knowledgeBaseRepository.save(kb);
            });

            log.info("知识库分块向量化完成: kbId={}, chunks={}", knowledgeBaseId, totalChunks);
        } catch (Exception e) {
            log.error("向量化知识库失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            throw new RuntimeException("向量化知识库失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 metadata 中的值转为 JSON / 标量，便于写入 vector_store。
     */
    private static Map<String, Object> normalizeMetadata(Map<String, Object> raw) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (v instanceof List<?> list) {
                out.put(e.getKey(), list.stream().map(Object::toString).toList());
            } else if (v instanceof Map<?, ?> map) {
                out.put(e.getKey(), map.toString());
            } else if (v instanceof Number || v instanceof Boolean) {
                out.put(e.getKey(), v);
            } else {
                out.put(e.getKey(), v.toString());
            }
        }
        return out;
    }

    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("向量相似度搜索: query={}, kbIds={}, topK={}, minScore={}",
            query, knowledgeBaseIds, topK, minScore);
        
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1));

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) {
                return List.of();
            }
            
            log.info("搜索完成: 找到 {} 个相关文档", results.size());
            return results;
            
        } catch (Exception e) {
            log.warn("向量搜索前置过滤失败，回退到本地过滤: {}", e.getMessage());
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
        }
    }

    private List<Document> similaritySearchFallback(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        try {
            // 回退检索仍保留 topK/minScore，避免兜底路径引入过多弱相关命中
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK * 3, topK));
            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            List<Document> allResults = vectorStore.similaritySearch(builder.build());
            if (allResults == null || allResults.isEmpty()) {
                return List.of();
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                allResults = allResults.stream()
                    .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                    .collect(Collectors.toList());
            }

            List<Document> results = allResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("回退检索完成: 找到 {} 个相关文档", results.size());
            return results;
        } catch (Exception e) {
            log.error("向量搜索失败: {}", e.getMessage(), e);
            throw new RuntimeException("向量搜索失败: " + e.getMessage(), e);
        }
    }

    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) {
            return false;
        }
        try {
            Long kbIdLong = kbId instanceof Long
                ? (Long) kbId
                : Long.parseLong(kbId.toString());
            return knowledgeBaseIds.contains(kbIdLong);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        String values = knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(", "));
        return "kb_id in [" + values + "]";
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            throw new RuntimeException("删除向量数据失败: " + e.getMessage(), e);
        }
    }

}
