package com.ash.springai.interview_platform.controller;

import com.ash.springai.interview_platform.Entity.LegacyCleanupResultDTO;
import com.ash.springai.interview_platform.service.KnowledgeBaseChunkBrowseService;
import com.ash.springai.interview_platform.service.KnowledgeBaseDeleteService;
import com.ash.springai.interview_platform.service.KnowledgeBaseListService;
import com.ash.springai.interview_platform.service.KnowledgeBaseQueryService;
import com.ash.springai.interview_platform.service.KnowledgeBaseUploadService;
import com.ash.springai.interview_platform.service.LegacyKnowledgeBaseCleanupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeBaseCleanupApiTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        KnowledgeBaseUploadService uploadService = mock(KnowledgeBaseUploadService.class);
        KnowledgeBaseQueryService queryService = mock(KnowledgeBaseQueryService.class);
        KnowledgeBaseListService listService = mock(KnowledgeBaseListService.class);
        KnowledgeBaseDeleteService deleteService = mock(KnowledgeBaseDeleteService.class);
        KnowledgeBaseChunkBrowseService chunkBrowseService = mock(KnowledgeBaseChunkBrowseService.class);
        LegacyKnowledgeBaseCleanupService cleanupService = mock(LegacyKnowledgeBaseCleanupService.class);

        when(cleanupService.cleanupLegacyKnowledgeBases(eq(10), anyString()))
            .thenReturn(new LegacyCleanupResultDTO(
                List.of(1L),
                List.of("kb_id=1,doc_name=a,delete_time=x,operator=ash"),
                Map.of()
            ));

        KnowledgeBaseController controller = new KnowledgeBaseController(
            uploadService,
            queryService,
            listService,
            deleteService,
            chunkBrowseService,
            cleanupService,
            new ObjectMapper()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldExposeLegacyCleanupEndpoint() throws Exception {
        mockMvc.perform(
                post("/api/knowledgebase/legacy/cleanup")
                    .param("batchSize", "10")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.auditRecords").isArray())
            .andExpect(jsonPath("$.data.deletedKnowledgeBaseIds[0]").value(1));
    }
}
