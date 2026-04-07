package com.ash.springai.interview_platform.service;

import com.ash.springai.interview_platform.Entity.ChunkItemDTO;
import com.ash.springai.interview_platform.Entity.DocumentChunksResponse;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseEntity;
import com.ash.springai.interview_platform.Repository.KnowledgeBaseRepository;
import com.ash.springai.interview_platform.Repository.VectorChunkBrowseRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseChunkBrowseServiceTests {

    @Test
    void shouldReturnDocumentChunksByKnowledgeBaseId() {
        KnowledgeBaseRepository kbRepo = mock(KnowledgeBaseRepository.class);
        VectorChunkBrowseRepository chunkRepo = mock(VectorChunkBrowseRepository.class);
        KnowledgeBaseChunkBrowseService service = new KnowledgeBaseChunkBrowseService(kbRepo, chunkRepo);

        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(1L);
        kb.setName("demo");
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb));
        when(chunkRepo.countByKnowledgeBaseId(1L)).thenReturn(1L);
        when(chunkRepo.findChunksByKnowledgeBaseId(eq(1L), anyInt(), anyInt()))
            .thenReturn(List.of(new ChunkItemDTO("c-1", 1, "p", "content", 7, 2, "{}")));
        when(chunkRepo.fetchStats(1L))
            .thenReturn(new VectorChunkBrowseRepository.ChunkStats(7.0, 7, 7));

        DocumentChunksResponse response = service.getDocumentChunks(1L, 1, 10, null);
        assertEquals("DOC_CHUNKS_DETAIL", response.mode());
        assertEquals(1L, response.document().id());
        assertEquals(1, response.chunkList().size());
    }
}
