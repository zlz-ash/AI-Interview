package com.ash.springai.interview_platform.listener;

import org.springframework.stereotype.Component;
import org.redisson.api.stream.StreamMessageId;

import lombok.extern.slf4j.Slf4j;

import com.ash.springai.interview_platform.Repository.KnowledgeBaseRepository;
import com.ash.springai.interview_platform.Entity.IngestChunkDTO;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseEntity;
import com.ash.springai.interview_platform.Repository.VectorRepository;
import com.ash.springai.interview_platform.service.ChunkEnrichService;
import com.ash.springai.interview_platform.service.ChunkSplitService;
import com.ash.springai.interview_platform.service.DocumentTypeRouterService;
import com.ash.springai.interview_platform.service.KnowledgeBaseParseService;
import com.ash.springai.interview_platform.service.KnowledgeBaseVectorService;
import com.ash.springai.interview_platform.redis.RedisService;
import com.ash.springai.interview_platform.common.AsyncTaskStreamConstants;
import com.ash.springai.interview_platform.enums.DocumentType;
import com.ash.springai.interview_platform.enums.VectorStatus;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class VectorizeStreamConsumer extends AbstractStreamConsumer<VectorizeStreamConsumer.VectorizePayload> {

    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseParseService parseService;
    private final DocumentTypeRouterService router;
    private final ChunkSplitService splitService;
    private final ChunkEnrichService enrichService;
    private final VectorRepository vectorRepository;

    public VectorizeStreamConsumer(
        RedisService redisService,
        KnowledgeBaseVectorService vectorService,
        KnowledgeBaseRepository knowledgeBaseRepository,
        KnowledgeBaseParseService parseService,
        DocumentTypeRouterService router,
        ChunkSplitService splitService,
        ChunkEnrichService enrichService,
        VectorRepository vectorRepository
    ) {
        super(redisService);
        this.vectorService = vectorService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.parseService = parseService;
        this.router = router;
        this.splitService = splitService;
        this.enrichService = enrichService;
        this.vectorRepository = vectorRepository;
    }

    record VectorizePayload(
        Long kbId,
        String storageKey,
        String originalFilename,
        String contentType,
        String ingestVersion
    ) {}

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "vectorize-consumer";
    }

    @Override
    protected VectorizePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String kbIdStr = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
        String storageKey = data.get(AsyncTaskStreamConstants.FIELD_STORAGE_KEY);
        String originalFilename = data.get(AsyncTaskStreamConstants.FIELD_ORIGINAL_FILENAME);
        String contentType = data.get(AsyncTaskStreamConstants.FIELD_CONTENT_TYPE);
        String ingestVersion = data.get(AsyncTaskStreamConstants.FIELD_INGEST_VERSION);

        if (kbIdStr == null) {
            log.warn("消息格式错误，缺少 kbId: messageId={}", messageId);
            return null;
        }
        if (storageKey == null || storageKey.isBlank()) {
            log.warn("消息格式错误，缺少 storageKey: messageId={}", messageId);
            return null;
        }
        if (originalFilename == null || originalFilename.isBlank()) {
            log.warn("消息格式错误，缺少 originalFilename: messageId={}", messageId);
            return null;
        }

        long kbId;
        try {
            kbId = Long.parseLong(kbIdStr.trim());
        } catch (NumberFormatException e) {
            log.warn("消息格式错误，kbId 非数字: messageId={}, kbIdStr={}", messageId, kbIdStr);
            return null;
        }

        return new VectorizePayload(
            kbId,
            storageKey,
            originalFilename,
            contentType == null ? "" : contentType,
            ingestVersion == null ? "" : ingestVersion
        );
    }

    @Override
    protected String payloadIdentifier(VectorizePayload payload) {
        return "kbId=" + payload.kbId();
    }

    @Override
    protected void markProcessing(VectorizePayload payload) {
        updateKnowledgeBaseState(payload.kbId(), VectorStatus.PROCESSING, null, "PROCESSING");
    }

    @Override
    protected void processBusiness(VectorizePayload payload) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(payload.kbId())
            .orElseThrow(() -> new IllegalStateException("知识库不存在"));
        String content = parseService.downloadAndParseContent(payload.storageKey(), payload.originalFilename());
        DocumentType type = router.route(payload.contentType(), payload.originalFilename(), content);
        syncDocumentTypeOnEntity(payload.kbId(), type, payload.ingestVersion());

        String tokenizerProfileId = kb.getTokenizerProfileId();
        List<IngestChunkDTO> chunks = (tokenizerProfileId == null || tokenizerProfileId.isBlank())
            ? splitService.split(type, content)
            : splitService.split(type, content, tokenizerProfileId);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("切分结果为空");
        }

        String kbIdStr = String.valueOf(payload.kbId());
        String docId = payload.storageKey() != null ? payload.storageKey() : kbIdStr;
        List<IngestChunkDTO> enriched = chunks.parallelStream()
            .map(c -> enrichService.enrich(c, type, kbIdStr, docId))
            .collect(Collectors.toList());

        validateEnrichedChunks(enriched); 

        vectorService.vectorizeAndStoreChunks(payload.kbId(), enriched);

        validateStoredVectors(payload.kbId());
    }

    private void validateEnrichedChunks(List<IngestChunkDTO> enriched) {
        for (IngestChunkDTO c : enriched) {
            if (c.content() == null || c.content().isBlank()) {
                throw new IllegalStateException("存在空内容分块");
            }
        }
    }

    private void validateStoredVectors(Long kbId) {
        long n = vectorRepository.countByKnowledgeBaseId(kbId);
        if (n <= 0) {
            log.warn("向量化校验: kbId={} 在 vector_store 中计数为 0（可能延迟可见）", kbId);
        }
    }

    private void syncDocumentTypeOnEntity(Long kbId, DocumentType type, String ingestVersion) {
        knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
            kb.setDocumentType(type);
            if (ingestVersion != null && !ingestVersion.isBlank()) {
                kb.setIngestVersion(ingestVersion);
            }
            knowledgeBaseRepository.save(kb);
        });
    }

    @Override
    protected void markCompleted(VectorizePayload payload) {
        updateKnowledgeBaseState(payload.kbId(), VectorStatus.COMPLETED, null, "COMPLETED");
    }

    @Override
    protected void markFailed(VectorizePayload payload, String error) {
        updateKnowledgeBaseState(payload.kbId(), VectorStatus.FAILED, error, "FAILED");
    }

    @Override
    protected void retryMessage(VectorizePayload payload, int retryCount) {
        Long kbId = payload.kbId();
        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_KB_ID, kbId.toString(),
                AsyncTaskStreamConstants.FIELD_STORAGE_KEY, nullToEmpty(payload.storageKey()),
                AsyncTaskStreamConstants.FIELD_ORIGINAL_FILENAME, nullToEmpty(payload.originalFilename()),
                AsyncTaskStreamConstants.FIELD_CONTENT_TYPE, nullToEmpty(payload.contentType()),
                AsyncTaskStreamConstants.FIELD_INGEST_VERSION, nullToEmpty(payload.ingestVersion()),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("向量化任务已重新入队: kbId={}, retryCount={}", kbId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: kbId={}, error={}", kbId, e.getMessage(), e);
            updateKnowledgeBaseState(kbId, VectorStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()), "FAILED");
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private void updateKnowledgeBaseState(Long kbId, VectorStatus status, String error, String ingestStatus) {
        try {
            knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                kb.setVectorStatus(status);
                if (ingestStatus != null) {
                    kb.setIngestStatus(ingestStatus);
                }
                kb.setVectorError(error);
                knowledgeBaseRepository.save(kb);
                log.debug("向量化状态已更新: kbId={}, status={}, ingestStatus={}", kbId, status, ingestStatus);
            });
        } catch (Exception e) {
            log.error("更新向量化状态失败: kbId={}, status={}, error={}", kbId, status, e.getMessage(), e);
        }
    }
}
