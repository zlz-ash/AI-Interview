package com.ash.springai.interview_platform.service;

import com.ash.springai.interview_platform.Entity.IngestChunkDTO;
import com.ash.springai.interview_platform.config.IngestProperties;
import com.ash.springai.interview_platform.enums.DocumentType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkEnrichServiceTests {

    @Test
    void shouldEnrichChunkWithKeywordsEntitiesAndQualityFlags() {
        IngestProperties props = new IngestProperties();
        ChunkEnrichService service = new ChunkEnrichService(props);
        IngestChunkDTO raw = new IngestChunkDTO(1, "Spring Boot 事务传播 REQUIRES_NEW", 24, new HashMap<>());
        IngestChunkDTO enriched = service.enrich(raw, DocumentType.MARKDOWN_TEXT, "kb-1", "doc-1");
        assertTrue(enriched.metadata().containsKey("keywords"));
        assertTrue(enriched.metadata().containsKey("entities"));
        assertTrue(enriched.metadata().containsKey("quality_flags"));
    }
}
