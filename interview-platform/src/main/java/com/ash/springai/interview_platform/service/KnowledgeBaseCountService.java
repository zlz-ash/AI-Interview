package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import com.ash.springai.interview_platform.Repository.KnowledgeBaseRepository;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseEntity;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseCountService {
    
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    @Transactional(rollbackFor = Exception.class)
    public void updateQuestionCounts(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return;
        }

        // 去重
        List<Long> uniqueIds = knowledgeBaseIds.stream().distinct().toList();

        // 验证所有知识库是否存在
        Set<Long> existingIds = new HashSet<>(knowledgeBaseRepository.findAllById(uniqueIds)
                .stream().map(KnowledgeBaseEntity::getId).toList());

        for (Long id : uniqueIds) {
            if (!existingIds.contains(id)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + id);
            }
        }

        // 批量更新（单条 SQL）
        int updated = knowledgeBaseRepository.incrementQuestionCountBatch(uniqueIds);
        log.debug("批量更新知识库提问计数: ids={}, updated={}", uniqueIds, updated);
    }

}
