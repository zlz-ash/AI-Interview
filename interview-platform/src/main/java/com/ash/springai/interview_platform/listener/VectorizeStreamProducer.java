package com.ash.springai.interview_platform.listener;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.ash.springai.interview_platform.common.AbstractStreamProducer;
import com.ash.springai.interview_platform.Repository.KnowledgeBaseRepository;
import com.ash.springai.interview_platform.redis.RedisService;
import com.ash.springai.interview_platform.common.AsyncTaskStreamConstants;
import com.ash.springai.interview_platform.enums.VectorStatus;

import java.util.Map;

@Slf4j
@Component
public class VectorizeStreamProducer extends AbstractStreamProducer<VectorizeStreamProducer.VectorizeTaskPayload> {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    record VectorizeTaskPayload(
        Long kbId,
        String storageKey,
        String originalFilename,
        String contentType,
        String ingestVersion
    ) {}

    public VectorizeStreamProducer(RedisService redisService, KnowledgeBaseRepository knowledgeBaseRepository) {
        super(redisService);
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    public void sendVectorizeTask(
        Long kbId,
        String storageKey,
        String originalFilename,
        String contentType,
        String ingestVersion
    ) {
        sendTask(new VectorizeTaskPayload(kbId, storageKey, originalFilename, contentType, ingestVersion));
    }

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(VectorizeTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
            AsyncTaskStreamConstants.FIELD_STORAGE_KEY, nullToEmpty(payload.storageKey()),
            AsyncTaskStreamConstants.FIELD_ORIGINAL_FILENAME, nullToEmpty(payload.originalFilename()),
            AsyncTaskStreamConstants.FIELD_CONTENT_TYPE, nullToEmpty(payload.contentType()),
            AsyncTaskStreamConstants.FIELD_INGEST_VERSION, nullToEmpty(payload.ingestVersion()),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override
    protected String payloadIdentifier(VectorizeTaskPayload payload) {
        return "kbId=" + payload.kbId();
    }

    @Override
    protected void onSendFailed(VectorizeTaskPayload payload, String error) {
        updateVectorStatus(payload.kbId(), VectorStatus.FAILED, truncateError(error));
    }

    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
            kb.setVectorStatus(status);
            kb.setIngestStatus("FAILED");
            if (error != null) {
                kb.setVectorError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            knowledgeBaseRepository.save(kb);
        });
    }
}
