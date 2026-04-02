package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import com.ash.springai.interview_platform.Repository.KnowledgeBaseRepository;
import com.ash.springai.interview_platform.Repository.RagChatSessionRepository;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseEntity;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;
import com.ash.springai.interview_platform.Entity.RagChatSessionEntity;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseDeleteService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatSessionRepository sessionRepository;
    private final KnowledgeBaseVectorService vectorService;
    private final FileStorageService storageService;
    
    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeBase(Long id){
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

        List<RagChatSessionEntity> sessions = sessionRepository.findByKnowledgeBaseIds(List.of(id));
        for (RagChatSessionEntity session : sessions) {
            session.getKnowledgeBases().removeIf(kbEntity -> kbEntity.getId().equals(id));
            sessionRepository.save(session);
            log.debug("已从会话中移除知识库关联: sessionId={}, kbId={}", session.getId(), id);
        }
        if (!sessions.isEmpty()) {
            log.info("已从 {} 个会话中移除知识库关联: kbId={}", sessions.size(), id);
        }

        try {
            vectorService.deleteByKnowledgeBaseId(id);
        } catch (Exception e) {
            log.warn("删除向量数据失败，继续删除知识库: kbId={}, error={}", id, e.getMessage());
        }

        try {
            storageService.deleteKnowledgeBase(kb.getStorageKey());
        } catch (Exception e) {
            log.warn("删除RustFS文件失败，继续删除知识库记录: kbId={}, error={}", id, e.getMessage());
        }

        knowledgeBaseRepository.deleteById(id);
        log.info("知识库已删除: id={}", id);


    }


}
