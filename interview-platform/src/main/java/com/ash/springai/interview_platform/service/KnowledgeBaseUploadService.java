package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.ash.springai.interview_platform.Repository.KnowledgeBaseRepository;
import com.ash.springai.interview_platform.config.IngestProperties;
import com.ash.springai.interview_platform.listener.VectorizeStreamProducer;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseEntity;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;
import com.ash.springai.interview_platform.enums.VectorStatus;
import com.ash.springai.interview_platform.service.chunking.TokenizerProfileRegistry;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseUploadService {
    
    private final KnowledgeBaseParseService parseService;
    private final FileStorageService storageService;
    private final KnowledgeBasePersistenceService persistenceService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final VectorizeStreamProducer vectorizeStreamProducer;
    private final DocumentTypeRouterService documentTypeRouterService;
    private final IngestProperties ingestProperties;
    private final TokenizerProfileRegistry tokenizerProfileRegistry;

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    public Map<String, Object> uploadKnowledgeBase(MultipartFile file, String name, String category, String tokenizerProfileId){

        if (tokenizerProfileId == null || tokenizerProfileId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "tokenizerProfileId 不能为空");
        }
        TokenizerProfileRegistry.ProfileView tokenizerProfile = requireTokenizerProfile(tokenizerProfileId);

        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");

        String fileName = file.getOriginalFilename();
        log.info("收到知识库上传请求: {}, 大小: {} bytes, category: {}", fileName, file.getSize(), category);

        String contentType = parseService.detectContentType(file);
        validateContentType(contentType, fileName);

        String fileHash = fileHashService.calculateHash(file);
        Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);
        if (existingKb.isPresent()) {
            log.info("检测到重复知识库: hash={}", fileHash);
            return persistenceService.handleDuplicateKnowledgeBase(existingKb.get(), fileHash);
        }

        String content = parseService.parseContent(file);
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容，请确保文件格式正确");
        }

        String fileKey = storageService.uploadKnowledgeBase(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("知识库已存储到RustFS: {}", fileKey);

        var docType = documentTypeRouterService.route(contentType, fileName, content);
        KnowledgeBaseEntity savedKb = persistenceService.saveKnowledgeBase(
            file, name, category, fileKey, fileUrl, fileHash,
            docType, ingestProperties.getVersion(),
            tokenizerProfile.id(), tokenizerProfile.model(), "v1"
        );

        vectorizeStreamProducer.sendVectorizeTask(
            savedKb.getId(),
            fileKey,
            fileName,
            contentType,
            ingestProperties.getVersion()
        );

        log.info("知识库上传完成，向量化任务已入队: {}, kbId={}", fileName, savedKb.getId());

        return Map.of(
            "knowledgeBase", Map.of(
                "id", savedKb.getId(),
                "name", savedKb.getName(),
                "category", savedKb.getCategory() != null ? savedKb.getCategory() : "",
                "fileSize", savedKb.getFileSize(),
                "contentLength", content.length(),
                "vectorStatus", VectorStatus.PENDING.name()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl
            ),
            "duplicate", false
        );

    }

    private TokenizerProfileRegistry.ProfileView requireTokenizerProfile(String tokenizerProfileId) {
        try {
            return tokenizerProfileRegistry.require(tokenizerProfileId);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }
    }

    private void validateContentType(String contentType, String fileName) {
        fileValidationService.validateContentType(
            contentType,
            fileName,
            fileValidationService::isKnowledgeBaseMimeType,
            fn -> fileValidationService.isMarkdownExtension(fn) || fileValidationService.isExcelExtension(fn),
            "不支持的文件类型: " + contentType + "，支持的类型：PDF、DOCX、DOC、TXT、MD、XLSX 等"
        );
    }

    public void revectorize(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

        log.info("开始重新向量化知识库: kbId={}, name={}", kbId, kb.getName());

        // 1. 下载文件并解析内容
        String content = parseService.downloadAndParseContent(kb.getStorageKey(), kb.getOriginalFilename());
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容");
        }

        // 2. 更新状态为 PENDING（通过单独的 Service 保证事务生效）
        persistenceService.updateVectorStatusToPending(kbId);

        // 3. 发送向量化任务到 Stream
        vectorizeStreamProducer.sendVectorizeTask(
            kbId,
            kb.getStorageKey(),
            kb.getOriginalFilename(),
            kb.getContentType(),
            ingestProperties.getVersion()
        );

        log.info("重新向量化任务已发送: kbId={}", kbId);
    }
}
