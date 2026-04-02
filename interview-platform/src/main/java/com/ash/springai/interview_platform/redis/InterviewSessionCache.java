package com.ash.springai.interview_platform.redis;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import lombok.Data;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

import com.ash.springai.interview_platform.Entity.InterviewSessionDTO.SessionStatus;
import com.ash.springai.interview_platform.Entity.InterviewQuestionDTO;

import java.time.Duration;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionCache {
    
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    private static final String SESSION_KEY_PREFIX = "interview:session:";

    private static final String RESUME_SESSION_KEY_PREFIX = "interview:resume:";

    private static final Duration SESSION_TTL = Duration.ofHours(24);

    @Data
    public static class CachedSession implements Serializable {
        private String sessionId;
        private String resumeText;
        private Long resumeId;
        private String questionsJson;  // 序列化的问题列表
        private int currentIndex;
        private SessionStatus status;

        public CachedSession() {
        }

        public CachedSession(String sessionId, String resumeText, Long resumeId,
                            List<InterviewQuestionDTO> questions, int currentIndex,
                            SessionStatus status, ObjectMapper objectMapper) {
            this.sessionId = sessionId;
            this.resumeText = resumeText;
            this.resumeId = resumeId;
            this.currentIndex = currentIndex;
            this.status = status;
            try {
                this.questionsJson = objectMapper.writeValueAsString(questions);
            } catch (JacksonException e) {
                throw new RuntimeException("序列化问题列表失败", e);
            }
        }

        public List<InterviewQuestionDTO> getQuestions(ObjectMapper objectMapper) {
            try {
                return objectMapper.readValue(questionsJson, new TypeReference<>() {});
            } catch (JacksonException e) {
                throw new RuntimeException("反序列化问题列表失败", e);
            }
        }
    }

    public void saveSession(String sessionId, String resumeText, Long resumeId,
                           List<InterviewQuestionDTO> questions, int currentIndex,
                           SessionStatus status) {
        String key = buildSessionKey(sessionId);
        CachedSession cachedSession = new CachedSession(
            sessionId, resumeText, resumeId, questions, currentIndex, status, objectMapper
        );

        redisService.set(key, cachedSession, SESSION_TTL);

        // 如果有 resumeId，建立映射关系（用于查找未完成会话）
        if (resumeId != null && isUnfinishedStatus(status)) {
            saveResumeSessionMapping(resumeId, sessionId);
        }

        log.debug("会话已缓存: sessionId={}, resumeId={}, status={}", sessionId, resumeId, status);
    }

    public Optional<CachedSession> getSession(String sessionId) {
        String key = buildSessionKey(sessionId);
        CachedSession session = redisService.get(key);
        if (session != null) {
            log.debug("从缓存获取会话: sessionId={}", sessionId);
            return Optional.of(session);
        }
        return Optional.empty();
    }

    public void updateSessionStatus(String sessionId, SessionStatus status) {
        getSession(sessionId).ifPresent(session -> {
            session.setStatus(status);
            String key = buildSessionKey(sessionId);
            redisService.set(key, session, SESSION_TTL);

            // 如果会话已完成，移除映射
            if (!isUnfinishedStatus(status) && session.getResumeId() != null) {
                removeResumeSessionMapping(session.getResumeId(), sessionId);
            }

            log.debug("更新会话状态: sessionId={}, status={}", sessionId, status);
        });
    }

    public void updateCurrentIndex(String sessionId, int currentIndex) {
        getSession(sessionId).ifPresent(session -> {
            session.setCurrentIndex(currentIndex);
            String key = buildSessionKey(sessionId);
            redisService.set(key, session, SESSION_TTL);
            log.debug("更新会话进度: sessionId={}, currentIndex={}", sessionId, currentIndex);
        });
    }

    public void updateQuestions(String sessionId, List<InterviewQuestionDTO> questions) {
        getSession(sessionId).ifPresent(session -> {
            try {
                session.setQuestionsJson(objectMapper.writeValueAsString(questions));
                String key = buildSessionKey(sessionId);
                redisService.set(key, session, SESSION_TTL);
                log.debug("更新会话问题: sessionId={}", sessionId);
            } catch (JacksonException e) {
                log.error("序列化问题列表失败", e);
            }
        });
    }

    public void deleteSession(String sessionId) {
        getSession(sessionId).ifPresent(session -> {
            if (session.getResumeId() != null) {
                removeResumeSessionMapping(session.getResumeId(), sessionId);
            }
        });

        String key = buildSessionKey(sessionId);
        redisService.delete(key);
        log.debug("删除会话缓存: sessionId={}", sessionId);
    }

    public Optional<String> findUnfinishedSessionId(Long resumeId) {
        String key = buildResumeSessionKey(resumeId);
        String sessionId = redisService.get(key);
        if (sessionId != null) {
            // 验证会话是否仍然存在且未完成
            Optional<CachedSession> sessionOpt = getSession(sessionId);
            if (sessionOpt.isPresent() && isUnfinishedStatus(sessionOpt.get().getStatus())) {
                return Optional.of(sessionId);
            } else {
                // 会话已不存在或已完成，清理映射
                redisService.delete(key);
            }
        }
        return Optional.empty();
    }

    public void refreshSessionTTL(String sessionId) {
        String key = buildSessionKey(sessionId);
        redisService.expire(key, SESSION_TTL);
    }

    public boolean exists(String sessionId) {
        String key = buildSessionKey(sessionId);
        return redisService.exists(key);
    }

    private String buildSessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private String buildResumeSessionKey(Long resumeId) {
        return RESUME_SESSION_KEY_PREFIX + resumeId;
    }

    private void saveResumeSessionMapping(Long resumeId, String sessionId) {
        String key = buildResumeSessionKey(resumeId);
        redisService.set(key, sessionId, SESSION_TTL);
    }

    private void removeResumeSessionMapping(Long resumeId, String sessionId) {
        String key = buildResumeSessionKey(resumeId);
        String currentSessionId = redisService.get(key);
        // 只有当前映射的是这个 sessionId 时才删除
        if (sessionId.equals(currentSessionId)) {
            redisService.delete(key);
        }
    }

    private boolean isUnfinishedStatus(SessionStatus status) {
        return status == SessionStatus.CREATED || status == SessionStatus.IN_PROGRESS;
    }
}
