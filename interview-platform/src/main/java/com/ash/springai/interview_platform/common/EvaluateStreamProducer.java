package com.ash.springai.interview_platform.common;

import org.springframework.stereotype.Component;

import com.ash.springai.interview_platform.Repository.InterviewSessionRepository;
import com.ash.springai.interview_platform.redis.RedisService;
import com.ash.springai.interview_platform.enums.AsyncTaskStatus;

import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class EvaluateStreamProducer extends AbstractStreamProducer<String> {
    
    private final InterviewSessionRepository sessionRepository;

    public EvaluateStreamProducer(RedisService redisService, InterviewSessionRepository sessionRepository) {
        super(redisService);
        this.sessionRepository = sessionRepository;
    }

    public void sendEvaluateTask(String sessionId) {
        sendTask(sessionId);
    }

    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(String sessionId) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId,
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(String sessionId) {
        return "sessionId=" + sessionId;
    }

    @Override
    protected void onSendFailed(String sessionId, String error) {
        updateEvaluateStatus(sessionId, AsyncTaskStatus.FAILED, truncateError(error));
    }

    private void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            sessionRepository.save(session);
        });
    }
}
