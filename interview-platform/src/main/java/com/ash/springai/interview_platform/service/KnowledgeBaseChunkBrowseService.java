package com.ash.springai.interview_platform.service;

import com.ash.springai.interview_platform.Entity.ChunkItemDTO;
import com.ash.springai.interview_platform.Entity.DocumentChunksResponse;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseEntity;
import com.ash.springai.interview_platform.Repository.KnowledgeBaseRepository;
import com.ash.springai.interview_platform.Repository.VectorChunkBrowseRepository;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseChunkBrowseService {

    public static final String MODE_DOC_CHUNKS_DETAIL = "DOC_CHUNKS_DETAIL";

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final VectorChunkBrowseRepository vectorChunkBrowseRepository;

    public DocumentChunksResponse getDocumentChunks(Long documentId, int page, int pageSize, String selectedChunkId) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = (safePage - 1) * safePageSize;

        // 方案 1：documentId == knowledgeBaseId
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(documentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "文档不存在"));

        long totalChunks = vectorChunkBrowseRepository.countByKnowledgeBaseId(documentId);
        List<ChunkItemDTO> chunkList = vectorChunkBrowseRepository.findChunksByKnowledgeBaseId(documentId, offset, safePageSize);
        ChunkItemDTO chunkDetail = resolveChunkDetail(documentId, selectedChunkId, chunkList);
        VectorChunkBrowseRepository.ChunkStats stats = vectorChunkBrowseRepository.fetchStats(documentId);

        return new DocumentChunksResponse(
            MODE_DOC_CHUNKS_DETAIL,
            new DocumentChunksResponse.DocumentInfo(kb.getId(), kb.getName(), kb.getId()),
            chunkList,
            chunkDetail,
            new DocumentChunksResponse.PageInfo(safePage, safePageSize, totalChunks, offset + chunkList.size() < totalChunks),
            new DocumentChunksResponse.StatsInfo(stats.avgChunkLength(), stats.minChunkLength(), stats.maxChunkLength())
        );
    }

    private ChunkItemDTO resolveChunkDetail(Long documentId, String selectedChunkId, List<ChunkItemDTO> chunkList) {
        if (selectedChunkId != null && !selectedChunkId.isBlank()) {
            return vectorChunkBrowseRepository.findChunkDetailById(documentId, selectedChunkId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_CHUNK_NOT_FOUND, "切片不存在"));
        }
        if (chunkList.isEmpty()) {
            return null;
        }
        return chunkList.getFirst();
    }
}
