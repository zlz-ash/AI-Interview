package com.ash.springai.interview_platform.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseEntity;
import com.ash.springai.interview_platform.Entity.LegacyCleanupResultDTO;
import com.ash.springai.interview_platform.Repository.KnowledgeBaseRepository;
import com.ash.springai.interview_platform.Repository.VectorRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyKnowledgeBaseCleanupService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final VectorRepository vectorRepository;
    private final FileStorageService fileStorageService;

    /**
     * 仅处理 {@code ingestVersion == null} 的遗留记录（按主键升序取一批），避免误删已走 v2 流水线的知识库。
     */
    public LegacyCleanupResultDTO cleanupLegacyKnowledgeBases(int batchSize, String operator) {
        int size = Math.max(1, batchSize);
        List<KnowledgeBaseEntity> batch = knowledgeBaseRepository.findByIngestVersionIsNullOrderByIdAsc(
            PageRequest.of(0, size));

        List<Long> deletedIds = new ArrayList<>();
        List<String> audits = new ArrayList<>();
        Map<Long, String> failures = new HashMap<>();
        String ts = LocalDateTime.now().format(TS);

        for (KnowledgeBaseEntity kb : batch) {
            Long id = kb.getId();
            try {
                vectorRepository.deleteByKnowledgeBaseId(id);
                if (kb.getStorageKey() != null && !kb.getStorageKey().isBlank()) {
                    fileStorageService.deleteKnowledgeBase(kb.getStorageKey());
                }
                knowledgeBaseRepository.delete(kb);
                deletedIds.add(id);
                audits.add(String.format(
                    "kb_id=%d,doc_name=%s,delete_time=%s,operator=%s",
                    id,
                    kb.getName() != null ? kb.getName() : kb.getOriginalFilename(),
                    ts,
                    operator != null ? operator : ""
                ));
                log.info("已清理知识库: kbId={}, operator={}", id, operator);
            } catch (Exception e) {
                log.warn("清理知识库失败: kbId={}, error={}", id, e.getMessage());
                failures.put(id, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }

        return new LegacyCleanupResultDTO(deletedIds, audits, failures);
    }
}
