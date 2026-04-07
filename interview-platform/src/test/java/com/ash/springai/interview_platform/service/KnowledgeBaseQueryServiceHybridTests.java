package com.ash.springai.interview_platform.service;

import com.ash.springai.interview_platform.Entity.HybridHitDTO;
import com.ash.springai.interview_platform.Entity.HybridMetaDTO;
import com.ash.springai.interview_platform.Entity.QueryResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBaseQueryServiceHybridTests {

    @Test
    void shouldSerializeQueryResponseWithHitsAndMeta() {
        QueryResponse response = QueryResponse.withSearch(
            "ok",
            1L,
            "示例文档",
            "QUERY_WITH_EMBEDDING",
            List.of(new HybridHitDTO("c-1", 1L, "vector", 0.91, "命中片段", "向量高相似")),
            new HybridMetaDTO(1, 0.30, 0.12, 0.75, 0.25, false)
        );
        assertEquals("QUERY_WITH_EMBEDDING", response.mode());
        assertEquals(1, response.hits().size());
        assertFalse(response.meta().lowConfidence());
    }

    @Test
    void shouldUseSemanticHeavyWeightsForNaturalQuestion() {
        QueryIntentClassifier classifier = new QueryIntentClassifier();
        QueryIntentClassifier.QueryIntent intent = classifier.classify("为什么 Spring 事务会失效");
        assertTrue(intent.semanticWeight() > intent.keywordWeight());
    }
}
