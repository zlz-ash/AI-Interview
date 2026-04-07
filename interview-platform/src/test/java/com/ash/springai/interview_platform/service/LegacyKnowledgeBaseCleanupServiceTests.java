package com.ash.springai.interview_platform.service;

import com.ash.springai.interview_platform.Entity.KnowledgeBaseEntity;
import com.ash.springai.interview_platform.Entity.LegacyCleanupResultDTO;
import com.ash.springai.interview_platform.Repository.KnowledgeBaseRepository;
import com.ash.springai.interview_platform.Repository.VectorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyKnowledgeBaseCleanupServiceTests {

    @Test
    void shouldDeleteLegacyDocsAndVectorsInBatch() {
        KnowledgeBaseRepository repo = mock(KnowledgeBaseRepository.class);
        VectorRepository vectorRepository = mock(VectorRepository.class);
        FileStorageService storage = mock(FileStorageService.class);

        KnowledgeBaseEntity e1 = new KnowledgeBaseEntity();
        e1.setId(1L);
        e1.setName("doc-a");
        e1.setStorageKey("kb/key1");

        when(repo.findByIngestVersionIsNullOrderByIdAsc(any(Pageable.class))).thenReturn(List.of(e1));

        LegacyKnowledgeBaseCleanupService cleanupService =
            new LegacyKnowledgeBaseCleanupService(repo, vectorRepository, storage);
        LegacyCleanupResultDTO result = cleanupService.cleanupLegacyKnowledgeBases(20, "ash");

        assertTrue(result.deletedKnowledgeBaseIds().size() <= 20);
        assertNotNull(result.auditRecords());
        verify(vectorRepository).deleteByKnowledgeBaseId(1L);
        verify(storage).deleteKnowledgeBase("kb/key1");
        verify(repo).delete(e1);
    }
}
