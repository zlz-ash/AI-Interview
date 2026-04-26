package com.ash.springai.interview_platform.service.chunking;

import com.ash.springai.interview_platform.Entity.IngestChunkDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkingCoreServiceTests {

    @Test
    void shouldCountAndTruncateByTokens() {
        TokenCounter counter = new JtokkitTokenCounter("cl100k_base");
        String text = "Java 面试：Explain Spring transaction propagation in detail.";
        int count = counter.count(text);
        assertTrue(count > 5);
        String truncated = counter.truncateToTokens(text, 5);
        assertTrue(counter.count(truncated) <= 5);
    }

    @Test
    void shouldSplitByTokenBudgetWithOverlap() {
        TokenCounter counter = new JtokkitTokenCounter("cl100k_base");
        ChunkBudgetPolicy policy = new ChunkBudgetPolicy(30, 5);
        ChunkingCoreService core = new ChunkingCoreService();

        StructuredChunkCandidate candidate = new StructuredChunkCandidate(
            "A > B", "H", "x ".repeat(200), Map.of("doc_type", "MARKDOWN")
        );
        List<IngestChunkDTO> chunks = core.chunk(List.of(candidate), policy, counter);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(it -> it.tokenEstimate() <= 30));
        assertTrue(chunks.size() > 1);
    }
}
