package com.ash.springai.interview_platform.service;

import com.ash.springai.interview_platform.Repository.KnowledgeBaseRepository;
import com.ash.springai.interview_platform.config.IngestProperties;
import com.ash.springai.interview_platform.config.TokenizerProfilesProperties;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.listener.VectorizeStreamProducer;
import com.ash.springai.interview_platform.service.chunking.TokenizerProfileRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class KnowledgeBaseUploadServiceTokenizerProfileTests {

    @Test
    void shouldThrowWhenTokenizerProfileMissing() {
        KnowledgeBaseUploadService service = newServiceWithRegistry(new TokenizerProfileRegistry(new TokenizerProfilesProperties()));
        MultipartFile file = mock(MultipartFile.class);
        assertThrows(BusinessException.class, () -> service.uploadKnowledgeBase(file, "n", "c", null));
    }

    @Test
    void shouldThrowWhenTokenizerProfileUnknown() {
        KnowledgeBaseUploadService service = newServiceWithRegistry(new TokenizerProfileRegistry(new TokenizerProfilesProperties()));
        MultipartFile file = mock(MultipartFile.class);
        assertThrows(BusinessException.class, () -> service.uploadKnowledgeBase(file, "n", "c", "unknown-profile"));
    }

    private static KnowledgeBaseUploadService newServiceWithRegistry(TokenizerProfileRegistry registry) {
        return new KnowledgeBaseUploadService(
            mock(KnowledgeBaseParseService.class),
            mock(FileStorageService.class),
            mock(KnowledgeBasePersistenceService.class),
            mock(KnowledgeBaseRepository.class),
            mock(FileValidationService.class),
            mock(FileHashService.class),
            mock(VectorizeStreamProducer.class),
            mock(DocumentTypeRouterService.class),
            new IngestProperties(),
            registry
        );
    }
}
