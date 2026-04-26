package com.ash.springai.interview_platform.controller;

import com.ash.springai.interview_platform.Entity.ChunkItemDTO;
import com.ash.springai.interview_platform.Entity.DocumentChunksResponse;
import com.ash.springai.interview_platform.service.KnowledgeBaseChunkBrowseService;
import com.ash.springai.interview_platform.service.KnowledgeBaseDeleteService;
import com.ash.springai.interview_platform.service.KnowledgeBaseListService;
import com.ash.springai.interview_platform.service.KnowledgeBaseUploadService;
import com.ash.springai.interview_platform.service.LegacyKnowledgeBaseCleanupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeBaseControllerChunkApiTests {

    private MockMvc mockMvc;
    private KnowledgeBaseChunkBrowseService chunkBrowseService;

    @BeforeEach
    void setUp() {
        KnowledgeBaseUploadService uploadService = mock(KnowledgeBaseUploadService.class);
        KnowledgeBaseListService listService = mock(KnowledgeBaseListService.class);
        KnowledgeBaseDeleteService deleteService = mock(KnowledgeBaseDeleteService.class);
        chunkBrowseService = mock(KnowledgeBaseChunkBrowseService.class);
        LegacyKnowledgeBaseCleanupService legacyCleanupService = mock(LegacyKnowledgeBaseCleanupService.class);

        KnowledgeBaseController controller = new KnowledgeBaseController(
            uploadService, listService, deleteService, chunkBrowseService, legacyCleanupService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldReturnDocumentChunksByDocumentId() throws Exception {
        when(chunkBrowseService.getDocumentChunks(eq(1L), eq(1), eq(10), isNull()))
            .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/knowledgebase/documents/1/chunks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.mode").value("DOC_CHUNKS_DETAIL"))
            .andExpect(jsonPath("$.data.chunkList[0].chunkIndex").value(1));
    }

    private DocumentChunksResponse sampleResponse() {
        ChunkItemDTO item = new ChunkItemDTO(
            "c-1", 1, "preview", "content", 7, 2, "{\"kb_id\":\"1\"}"
        );
        return new DocumentChunksResponse(
            "DOC_CHUNKS_DETAIL",
            new DocumentChunksResponse.DocumentInfo(1L, "Java 面试知识库", 1L),
            List.of(item),
            item,
            new DocumentChunksResponse.PageInfo(1, 10, 1, false),
            new DocumentChunksResponse.StatsInfo(7.0, 7, 7)
        );
    }
}
