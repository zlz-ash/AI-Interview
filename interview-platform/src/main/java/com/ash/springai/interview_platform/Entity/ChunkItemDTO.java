package com.ash.springai.interview_platform.Entity;

public record ChunkItemDTO(
    String chunkId,
    int chunkIndex,
    String preview,
    String content,
    int length,
    Integer tokenEstimate,
    String metadata
) {}
