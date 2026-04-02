package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import com.ash.springai.interview_platform.Repository.RagChatSessionRepository;
import com.ash.springai.interview_platform.Repository.RagChatMessageRepository;
import com.ash.springai.interview_platform.Repository.KnowledgeBaseRepository;
import com.ash.springai.interview_platform.mapper.RagChatMapper;
import com.ash.springai.interview_platform.mapper.KnowledgeBaseMapper;
import com.ash.springai.interview_platform.Entity.RagChatDTO.CreateSessionRequest;
import com.ash.springai.interview_platform.Entity.RagChatDTO.SessionDTO;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseEntity;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;
import com.ash.springai.interview_platform.Entity.RagChatSessionEntity;
import com.ash.springai.interview_platform.Entity.RagChatDTO.SessionListItemDTO;
import com.ash.springai.interview_platform.Entity.RagChatDTO.SessionDetailDTO;
import com.ash.springai.interview_platform.Entity.RagChatMessageEntity;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseListItemDTO;

import java.util.List;
import java.util.HashSet;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatSessionService {
    
    private final RagChatSessionRepository sessionRepository;
    private final RagChatMessageRepository messageRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQueryService queryService;
    private final RagChatMapper ragChatMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Transactional
    public SessionDTO createSession(CreateSessionRequest request) {
        // 验证知识库存在
        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
            .findAllById(request.knowledgeBaseIds());

        if (knowledgeBases.size() != request.knowledgeBaseIds().size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分知识库不存在");
        }

        // 创建会话
        RagChatSessionEntity session = new RagChatSessionEntity();
        session.setTitle(request.title() != null && !request.title().isBlank()
            ? request.title()
            : generateTitle(knowledgeBases));
        session.setKnowledgeBases(new HashSet<>(knowledgeBases));

        session = sessionRepository.save(session);

        log.info("创建 RAG 聊天会话: id={}, title={}", session.getId(), session.getTitle());

        return ragChatMapper.toSessionDTO(session);
    }

    public List<SessionListItemDTO> listSessions() {
        return sessionRepository.findAllOrderByPinnedAndUpdatedAtDesc()
            .stream()
            .map(ragChatMapper::toSessionListItemDTO)
            .toList();
    }

    public SessionDetailDTO getSessionDetail(Long sessionId) {
        // 先加载会话和知识库
        RagChatSessionEntity session = sessionRepository
            .findByIdWithKnowledgeBases(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 再单独加载消息（避免笛卡尔积）
        List<RagChatMessageEntity> messages = messageRepository
            .findBySessionIdOrderByMessageOrderAsc(sessionId);

        // 转换知识库列表
        List<KnowledgeBaseListItemDTO> kbDTOs = knowledgeBaseMapper.toListItemDTOList(
            new java.util.ArrayList<>(session.getKnowledgeBases())
        );

        return ragChatMapper.toSessionDetailDTO(session, messages, kbDTOs);
    }

    @Transactional
    public Long prepareStreamMessage(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 获取当前消息数量作为起始顺序
        int nextOrder = session.getMessageCount();

        // 保存用户消息
        RagChatMessageEntity userMessage = new RagChatMessageEntity();
        userMessage.setSession(session);
        userMessage.setType(RagChatMessageEntity.MessageType.USER);
        userMessage.setContent(question);
        userMessage.setMessageOrder(nextOrder);
        userMessage.setCompleted(true);
        messageRepository.save(userMessage);

        // 创建 AI 消息占位（未完成）
        RagChatMessageEntity assistantMessage = new RagChatMessageEntity();
        assistantMessage.setSession(session);
        assistantMessage.setType(RagChatMessageEntity.MessageType.ASSISTANT);
        assistantMessage.setContent("");
        assistantMessage.setMessageOrder(nextOrder + 1);
        assistantMessage.setCompleted(false);
        assistantMessage = messageRepository.save(assistantMessage);

        // 更新会话消息数量
        session.setMessageCount(nextOrder + 2);
        sessionRepository.save(session);

        log.info("准备流式消息: sessionId={}, messageId={}", sessionId, assistantMessage.getId());

        return assistantMessage.getId();
    }

    @Transactional
    public void completeStreamMessage(Long messageId, String content) {
        RagChatMessageEntity message = messageRepository.findById(messageId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "消息不存在"));

        message.setContent(content);
        message.setCompleted(true);
        messageRepository.save(message);

        log.info("完成流式消息: messageId={}, contentLength={}", messageId, content.length());
    }

    public Flux<String> getStreamAnswer(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<Long> kbIds = session.getKnowledgeBaseIds();

        return queryService.answerQuestionStream(kbIds, question);
    }

    @Transactional
    public void updateSessionTitle(Long sessionId, String title) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        session.setTitle(title);
        sessionRepository.save(session);

        log.info("更新会话标题: sessionId={}, title={}", sessionId, title);
    }

    @Transactional
    public void togglePin(Long sessionId) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 处理 null 值（兼容旧数据）
        Boolean currentPinned = session.getIsPinned() != null ? session.getIsPinned() : false;
        session.setIsPinned(!currentPinned);
        sessionRepository.save(session);

        log.info("切换会话置顶状态: sessionId={}, isPinned={}", sessionId, session.getIsPinned());
    }

    @Transactional
    public void updateSessionKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
            .findAllById(knowledgeBaseIds);

        session.setKnowledgeBases(new HashSet<>(knowledgeBases));
        sessionRepository.save(session);

        log.info("更新会话知识库: sessionId={}, kbIds={}", sessionId, knowledgeBaseIds);
    }

    @Transactional
    public void deleteSession(Long sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        sessionRepository.deleteById(sessionId);

        log.info("删除会话: sessionId={}", sessionId);
    }

    private String generateTitle(List<KnowledgeBaseEntity> knowledgeBases) {
        if (knowledgeBases.isEmpty()) {
            return "新对话";
        }
        if (knowledgeBases.size() == 1) {
            return knowledgeBases.getFirst().getName();
        }
        return knowledgeBases.size() + " 个知识库对话";
    }

}
