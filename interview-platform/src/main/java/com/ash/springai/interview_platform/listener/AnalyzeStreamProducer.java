package com.ash.springai.interview_platform.listener;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.ash.springai.interview_platform.Repository.ResumeRepository;
import com.ash.springai.interview_platform.common.AbstractStreamProducer;
import com.ash.springai.interview_platform.redis.RedisService;
import com.ash.springai.interview_platform.common.AsyncTaskStreamConstants;
import com.ash.springai.interview_platform.enums.AsyncTaskStatus;

import java.util.Map;

@Slf4j
@Component
public class AnalyzeStreamProducer extends AbstractStreamProducer<AnalyzeStreamProducer.AnalyzeTaskPayload> {
    
    private final ResumeRepository resumeRepository;

    record AnalyzeTaskPayload(Long resumeId, String content) {}

    public AnalyzeStreamProducer(RedisService redisService, ResumeRepository resumeRepository) {
        super(redisService);
        this.resumeRepository = resumeRepository;
    }

    public void sendAnalyzeTask(Long resumeId, String content) {
        sendTask(new AnalyzeTaskPayload(resumeId, content));
    }

    @Override
    protected String taskDisplayName() {
        return "分析";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(AnalyzeTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_RESUME_ID, payload.resumeId().toString(),
            AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(AnalyzeTaskPayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    @Override
    protected void onSendFailed(AnalyzeTaskPayload payload, String error) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, truncateError(error));
    }

    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
        resumeRepository.findById(resumeId).ifPresent(resume -> {
            resume.setAnalyzeStatus(status);
            if (error != null) {
                resume.setAnalyzeError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            resumeRepository.save(resume);
        });
    }
}
