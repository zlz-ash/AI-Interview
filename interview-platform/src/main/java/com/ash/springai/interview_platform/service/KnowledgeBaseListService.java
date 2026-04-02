package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import com.ash.springai.interview_platform.Repository.KnowledgeBaseRepository;
import com.ash.springai.interview_platform.Repository.RagChatMessageRepository;
import com.ash.springai.interview_platform.mapper.KnowledgeBaseMapper;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseListItemDTO;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseEntity;
import com.ash.springai.interview_platform.enums.VectorStatus;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseStatsDTO;
import com.ash.springai.interview_platform.Entity.RagChatMessageEntity.MessageType;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseListService {
    
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatMessageRepository ragChatMessageRepository;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final FileStorageService fileStorageService;

    public List<KnowledgeBaseListItemDTO> listKnowledgeBases(VectorStatus vectorStatus, String sortBy) {
        List<KnowledgeBaseEntity> entities;
        
        // 如果指定了状态，按状态过滤
        if (vectorStatus != null) {
            entities = knowledgeBaseRepository.findByVectorStatusOrderByUploadedAtDesc(vectorStatus);
        } else {
            // 否则获取所有知识库
            entities = knowledgeBaseRepository.findAllByOrderByUploadedAtDesc();
        }
        
        // 如果指定了排序字段，在内存中排序
        if (sortBy != null && !sortBy.isBlank() && !sortBy.equalsIgnoreCase("time")) {
            entities = sortEntities(entities, sortBy);
        }
        
        return knowledgeBaseMapper.toListItemDTOList(entities);
    }

    public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
        return listKnowledgeBases(null, null);
    }

    public List<KnowledgeBaseListItemDTO> listKnowledgeBasesByStatus(VectorStatus vectorStatus) {
        return listKnowledgeBases(vectorStatus, null);
    }

    public Optional<KnowledgeBaseListItemDTO> getKnowledgeBase(Long id) {
        return knowledgeBaseRepository.findById(id)
            .map(knowledgeBaseMapper::toListItemDTO);
    }

    public Optional<KnowledgeBaseEntity> getKnowledgeBaseEntity(Long id) {
        return knowledgeBaseRepository.findById(id);
    }

    public List<String> getKnowledgeBaseNames(List<Long> ids) {
        return ids.stream()
            .map(id -> knowledgeBaseRepository.findById(id)
                .map(KnowledgeBaseEntity::getName)
                .orElse("未知知识库"))
            .toList();
    }

    public List<String> getAllCategories() {
        return knowledgeBaseRepository.findAllCategories();
    }

    public List<KnowledgeBaseListItemDTO> listByCategory(String category) {
        List<KnowledgeBaseEntity> entities;
        if (category == null || category.isBlank()) {
            entities = knowledgeBaseRepository.findByCategoryIsNullOrderByUploadedAtDesc();
        } else {
            entities = knowledgeBaseRepository.findByCategoryOrderByUploadedAtDesc(category);
        }
        return knowledgeBaseMapper.toListItemDTOList(entities);
    }

    @Transactional
    public void updateCategory(Long id, String category) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("知识库不存在"));
        entity.setCategory(category != null && !category.isBlank() ? category : null);
        knowledgeBaseRepository.save(entity);
        log.info("更新知识库分类: id={}, category={}", id, category);
    }

    public List<KnowledgeBaseListItemDTO> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return listKnowledgeBases();
        }
        return knowledgeBaseMapper.toListItemDTOList(
            knowledgeBaseRepository.searchByKeyword(keyword.trim())
        );
    }

    public List<KnowledgeBaseListItemDTO> listSorted(String sortBy) {
        return listKnowledgeBases(null, sortBy);
    }

    private List<KnowledgeBaseEntity> sortEntities(List<KnowledgeBaseEntity> entities, String sortBy) {
        return switch (sortBy.toLowerCase()) {
            case "size" -> entities.stream()
                .sorted((a, b) -> Long.compare(b.getFileSize(), a.getFileSize()))
                .toList();
            case "access" -> entities.stream()
                .sorted((a, b) -> Integer.compare(b.getAccessCount(), a.getAccessCount()))
                .toList();
            case "question" -> entities.stream()
                .sorted((a, b) -> Integer.compare(b.getQuestionCount(), a.getQuestionCount()))
                .toList();
            default -> entities; // time 已经在数据库层面排序了
        };
    }

    public KnowledgeBaseStatsDTO getStatistics() {
        return new KnowledgeBaseStatsDTO(
            knowledgeBaseRepository.count(),
            ragChatMessageRepository.countByType(MessageType.USER),  // 真正的提问次数
            knowledgeBaseRepository.sumAccessCount(),
            knowledgeBaseRepository.countByVectorStatus(VectorStatus.COMPLETED),
            knowledgeBaseRepository.countByVectorStatus(VectorStatus.PROCESSING)
        );
    }

    public byte[] downloadFile(Long id) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));

        String storageKey = entity.getStorageKey();
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件存储信息不存在");
        }

        log.info("下载知识库文件: id={}, filename={}", id, entity.getOriginalFilename());
        return fileStorageService.downloadFile(storageKey);
    }

    public KnowledgeBaseEntity getEntityForDownload(Long id) {
        return knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
    }
}
