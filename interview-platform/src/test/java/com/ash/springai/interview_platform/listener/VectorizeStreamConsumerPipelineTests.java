package com.ash.springai.interview_platform.listener;

import com.ash.springai.interview_platform.Entity.IngestChunkDTO;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseEntity;
import com.ash.springai.interview_platform.Repository.KnowledgeBaseRepository;
import com.ash.springai.interview_platform.Repository.VectorRepository;
import com.ash.springai.interview_platform.common.AsyncTaskStreamConstants;
import com.ash.springai.interview_platform.enums.DocumentType;
import com.ash.springai.interview_platform.redis.RedisService;
import com.ash.springai.interview_platform.service.ChunkEnrichService;
import com.ash.springai.interview_platform.service.ChunkSplitService;
import com.ash.springai.interview_platform.service.DocumentTypeRouterService;
import com.ash.springai.interview_platform.service.KnowledgeBaseParseService;
import com.ash.springai.interview_platform.service.KnowledgeBaseVectorService;
import org.junit.jupiter.api.Test;
import org.redisson.api.stream.StreamMessageId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorizeStreamConsumerPipelineTests {

    @Test
    void shouldRunFiveStagePipelineInConsumer() {
        KnowledgeBaseVectorService vectorService = mock(KnowledgeBaseVectorService.class);
        KnowledgeBaseRepository kbRepo = mock(KnowledgeBaseRepository.class);
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(1L);
        kb.setTokenizerProfileId("dashscope-text-embedding-v3");
        when(kbRepo.findById(1L)).thenReturn(java.util.Optional.of(kb));
        KnowledgeBaseParseService parseService = mock(KnowledgeBaseParseService.class);
        when(parseService.downloadAndParseContent(anyString(), anyString())).thenReturn("parsed-body");
        DocumentTypeRouterService router = mock(DocumentTypeRouterService.class);
        when(router.route(any(), any(), anyString())).thenReturn(DocumentType.PDF_LONGFORM);
        ChunkSplitService splitService = mock(ChunkSplitService.class);
        when(splitService.split(any(), anyString(), anyString())).thenReturn(
            List.of(new IngestChunkDTO(1, "chunk", 2, new HashMap<>()))
        );
        ChunkEnrichService enrichService = mock(ChunkEnrichService.class);
        when(enrichService.enrich(any(), any(), anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        VectorRepository vectorRepository = mock(VectorRepository.class);
        when(vectorRepository.countByKnowledgeBaseId(1L)).thenReturn(1L);
        RedisService redis = mock(RedisService.class);

        VectorizeStreamConsumer consumer = new VectorizeStreamConsumer(
            redis,
            vectorService,
            kbRepo,
            parseService,
            router,
            splitService,
            enrichService,
            vectorRepository
        );

        Map<String, String> message = Map.of(
            AsyncTaskStreamConstants.FIELD_KB_ID, "1",
            AsyncTaskStreamConstants.FIELD_STORAGE_KEY, "kb/1/demo.pdf",
            AsyncTaskStreamConstants.FIELD_ORIGINAL_FILENAME, "demo.pdf",
            AsyncTaskStreamConstants.FIELD_CONTENT_TYPE, "application/pdf",
            AsyncTaskStreamConstants.FIELD_INGEST_VERSION, "v2"
        );
        VectorizeStreamConsumer.VectorizePayload payload =
            consumer.parsePayload(mock(StreamMessageId.class), message);
        consumer.processBusiness(payload);

        verify(parseService).downloadAndParseContent("kb/1/demo.pdf", "demo.pdf");
        verify(splitService).split(any(), eq("parsed-body"), eq("dashscope-text-embedding-v3"));
        verify(enrichService, atLeastOnce()).enrich(any(), any(), anyString(), anyString());
        verify(vectorService).vectorizeAndStoreChunks(eq(1L), anyList());
    }
}
