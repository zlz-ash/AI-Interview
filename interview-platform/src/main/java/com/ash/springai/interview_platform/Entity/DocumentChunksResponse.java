package com.ash.springai.interview_platform.Entity;

import java.util.List;

public record DocumentChunksResponse(
    String mode,
    DocumentInfo document,
    List<ChunkItemDTO> chunkList,
    ChunkItemDTO chunkDetail,
    PageInfo page,
    StatsInfo stats
) {
    public record DocumentInfo(
        Long id,
        String name,
        Long knowledgeBaseId
    ) {}

    public record PageInfo(
        int page,
        int pageSize,
        long totalChunks,
        boolean hasNext
    ) {}

    public record StatsInfo(
        double avgChunkLength,
        int minChunkLength,
        int maxChunkLength
    ) {}
}
