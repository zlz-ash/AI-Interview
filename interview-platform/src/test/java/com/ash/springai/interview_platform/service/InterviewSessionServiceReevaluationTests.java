package com.ash.springai.interview_platform.service;

import com.ash.springai.interview_platform.Entity.InterviewSessionDTO.SessionStatus;
import com.ash.springai.interview_platform.common.EvaluateStreamProducer;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;
import com.ash.springai.interview_platform.redis.InterviewSessionCache;
import com.ash.springai.interview_platform.redis.InterviewSessionCache.CachedSession;
import com.ash.springai.interview_platform.enums.AsyncTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewSessionServiceReevaluationTests {

    private InterviewSessionService service;
    private InterviewSessionCache sessionCache;
    private InterviewPersistenceService persistenceService;
    private EvaluateStreamProducer evaluateStreamProducer;

    @BeforeEach
    void setUp() {
        InterviewQuestionService questionService = mock(InterviewQuestionService.class);
        AnswerEvaluationService evaluationService = mock(AnswerEvaluationService.class);
        this.persistenceService = mock(InterviewPersistenceService.class);
        this.sessionCache = mock(InterviewSessionCache.class);
        ObjectMapper objectMapper = new ObjectMapper();
        this.evaluateStreamProducer = mock(EvaluateStreamProducer.class);
        this.service = new InterviewSessionService(
            questionService,
            evaluationService,
            persistenceService,
            sessionCache,
            objectMapper,
            evaluateStreamProducer
        );
    }

    @Test
    void shouldEnqueueReevaluationForCompletedSession() {
        String sessionId = "s-completed";
        when(sessionCache.getSession(sessionId)).thenReturn(Optional.of(cached(sessionId, SessionStatus.COMPLETED)));

        service.requestReevaluation(sessionId);

        verify(persistenceService).updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        verify(evaluateStreamProducer).sendEvaluateTask(sessionId);
    }

    @Test
    void shouldRejectReevaluationWhenInterviewNotCompleted() {
        String sessionId = "s-in-progress";
        when(sessionCache.getSession(sessionId)).thenReturn(Optional.of(cached(sessionId, SessionStatus.IN_PROGRESS)));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.requestReevaluation(sessionId)
        );

        assertEquals(ErrorCode.INTERVIEW_NOT_COMPLETED.getCode(), exception.getCode());
        verify(persistenceService, never()).updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        verify(evaluateStreamProducer, never()).sendEvaluateTask(sessionId);
    }

    private CachedSession cached(String sessionId, SessionStatus status) {
        CachedSession session = new CachedSession();
        session.setSessionId(sessionId);
        session.setResumeText("resume");
        session.setStatus(status);
        session.setCurrentIndex(0);
        session.setQuestionsJson("[]");
        return session;
    }
}
