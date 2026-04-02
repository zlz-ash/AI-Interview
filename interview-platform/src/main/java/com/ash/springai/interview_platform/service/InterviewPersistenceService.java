package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import com.ash.springai.interview_platform.Repository.InterviewSessionRepository;
import com.ash.springai.interview_platform.Repository.InterviewAnswerRepository;
import com.ash.springai.interview_platform.Repository.ResumeRepository;
import com.ash.springai.interview_platform.Entity.InterviewSessionEntity;
import com.ash.springai.interview_platform.Entity.ResumeEntity;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;
import com.ash.springai.interview_platform.Entity.InterviewQuestionDTO;
import com.ash.springai.interview_platform.Entity.InterviewAnswerEntity;
import com.ash.springai.interview_platform.enums.AsyncTaskStatus;
import com.ash.springai.interview_platform.Entity.InterviewReportDTO;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPersistenceService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final ResumeRepository resumeRepository;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveSession(String sessionId, Long resumeId, 
            int totalQuestions, 
            List<InterviewQuestionDTO> questions){

                try {
                    Optional<ResumeEntity> resumeOpt = resumeRepository.findById(resumeId);
                    if (resumeOpt.isEmpty()) {
                        throw new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在");
                    }
                    
                    InterviewSessionEntity session = new InterviewSessionEntity();
                    session.setSessionId(sessionId);
                    session.setResume(resumeOpt.get());
                    session.setTotalQuestions(totalQuestions);
                    session.setCurrentQuestionIndex(0);
                    session.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
                    session.setQuestionsJson(objectMapper.writeValueAsString(questions));
                    
                    InterviewSessionEntity saved = sessionRepository.save(session);
                    log.info("面试会话已保存: sessionId={}, resumeId={}", sessionId, resumeId);
                    
                    return saved;
                } catch (JacksonException e) {
                    log.error("序列化问题列表失败: {}", e.getMessage(), e);
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存会话失败");
                }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateSessionStatus(String sessionId, InterviewSessionEntity.SessionStatus status) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setStatus(status);
            if (status == InterviewSessionEntity.SessionStatus.COMPLETED ||
                status == InterviewSessionEntity.SessionStatus.EVALUATED) {
                session.setCompletedAt(LocalDateTime.now());
            }
            sessionRepository.save(session);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            } else {
                session.setEvaluateError(null);
            }
            sessionRepository.save(session);
            log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateCurrentQuestionIndex(String sessionId, int index) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setCurrentQuestionIndex(index);
            session.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            sessionRepository.save(session);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public InterviewAnswerEntity saveAnswer(String sessionId, int questionIndex,
                                            String question, String category,
                                            String userAnswer, int score, String feedback) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewAnswerEntity answer = answerRepository
            .findBySession_SessionIdAndQuestionIndex(sessionId, questionIndex)
            .orElseGet(() -> {
                InterviewAnswerEntity created = new InterviewAnswerEntity();
                created.setSession(sessionOpt.get());
                created.setQuestionIndex(questionIndex);
                return created;
            });

        answer.setQuestion(question);
        answer.setCategory(category);
        answer.setUserAnswer(userAnswer);
        answer.setScore(score);
        answer.setFeedback(feedback);

        InterviewAnswerEntity saved = answerRepository.save(answer);
        log.info("面试答案已保存: sessionId={}, questionIndex={}, score={}", 
                sessionId, questionIndex, score);
        
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveReport(String sessionId, InterviewReportDTO report) {
        try {
            Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
            if (sessionOpt.isEmpty()) {
                log.warn("会话不存在: {}", sessionId);
                return;
            }

            InterviewSessionEntity session = sessionOpt.get();
            session.setOverallScore(report.overallScore());
            session.setOverallFeedback(report.overallFeedback());
            session.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
            session.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
            session.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
            session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
            session.setCompletedAt(LocalDateTime.now());

            sessionRepository.save(session);

            // 查询已存在的答案，建立索引
            List<InterviewAnswerEntity> existingAnswers = answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
            java.util.Map<Integer, InterviewAnswerEntity> answerMap = existingAnswers.stream()
                .collect(java.util.stream.Collectors.toMap(
                    InterviewAnswerEntity::getQuestionIndex,
                    a -> a,
                    (a1, a2) -> a1
                ));

            // 建立参考答案索引
            java.util.Map<Integer, InterviewReportDTO.ReferenceAnswer> refAnswerMap = report.referenceAnswers().stream()
                .collect(java.util.stream.Collectors.toMap(
                    InterviewReportDTO.ReferenceAnswer::questionIndex,
                    r -> r,
                    (r1, r2) -> r1
                ));

            List<InterviewAnswerEntity> answersToSave = new java.util.ArrayList<>();

            // 遍历所有评估结果，更新或创建答案记录
            for (InterviewReportDTO.QuestionEvaluation eval : report.questionDetails()) {
                InterviewAnswerEntity answer = answerMap.get(eval.questionIndex());

                if (answer == null) {
                    // 未回答的题目，创建新记录
                    answer = new InterviewAnswerEntity();
                    answer.setSession(session);
                    answer.setQuestionIndex(eval.questionIndex());
                    answer.setQuestion(eval.question());
                    answer.setCategory(eval.category());
                    answer.setUserAnswer(null);  // 未回答
                    log.debug("为未回答的题目 {} 创建答案记录", eval.questionIndex());
                }

                // 更新评分和反馈
                answer.setScore(eval.score());
                answer.setFeedback(eval.feedback());

                // 设置参考答案和关键点
                InterviewReportDTO.ReferenceAnswer refAns = refAnswerMap.get(eval.questionIndex());
                if (refAns != null) {
                    answer.setReferenceAnswer(refAns.referenceAnswer());
                    if (refAns.keyPoints() != null && !refAns.keyPoints().isEmpty()) {
                        answer.setKeyPointsJson(objectMapper.writeValueAsString(refAns.keyPoints()));
                    }
                }

                answersToSave.add(answer);
            }

            answerRepository.saveAll(answersToSave);
            log.info("面试报告已保存: sessionId={}, score={}, 答案数={}",
                sessionId, report.overallScore(), answersToSave.size());

        } catch (JacksonException e) {
            log.error("序列化报告失败: {}", e.getMessage(), e);
        }
    }

    public Optional<InterviewSessionEntity> findBySessionId(String sessionId) {
        return sessionRepository.findBySessionId(sessionId);
    }

    public List<InterviewSessionEntity> findByResumeId(Long resumeId) {
        return sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionsByResumeId(Long resumeId) {
        List<InterviewSessionEntity> sessions = sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
        if (!sessions.isEmpty()) {
            sessionRepository.deleteAll(sessions);
            log.info("已删除 {} 个面试会话（包含所有答案）", sessions.size());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionBySessionId(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            sessionRepository.delete(sessionOpt.get());
            log.info("已删除面试会话: sessionId={}", sessionId);
        } else {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }
    }

    public Optional<InterviewSessionEntity> findUnfinishedSession(Long resumeId) {
        List<InterviewSessionEntity.SessionStatus> unfinishedStatuses = List.of(
            InterviewSessionEntity.SessionStatus.CREATED,
            InterviewSessionEntity.SessionStatus.IN_PROGRESS
        );
        return sessionRepository.findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(resumeId, unfinishedStatuses);
    }

    public List<InterviewAnswerEntity> findAnswersBySessionId(String sessionId) {
        return answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
    }

    public List<String> getHistoricalQuestionsByResumeId(Long resumeId) {
        // 只查询最近的 10 个会话，避免加载过多历史数据
        List<InterviewSessionEntity> sessions = sessionRepository.findTop10ByResumeIdOrderByCreatedAtDesc(resumeId);
        
        return sessions.stream()
            .map(InterviewSessionEntity::getQuestionsJson)
            .filter(json -> json != null && !json.isEmpty())
            .flatMap(json -> {
                try {
                    List<InterviewQuestionDTO> questions = objectMapper.readValue(json, 
                        new TypeReference<List<InterviewQuestionDTO>>() {});
                    // 过滤掉追问，只保留主问题作为历史参考
                    return questions.stream()
                        .filter(q -> !q.isFollowUp())
                        .map(InterviewQuestionDTO::question);
                } catch (Exception e) {
                    log.error("解析历史问题JSON失败", e);
                    return java.util.stream.Stream.empty();
                }
            })
            .distinct()
            .limit(30) // 核心改动：只保留最近的 30 道题
            .toList();
    }
}
