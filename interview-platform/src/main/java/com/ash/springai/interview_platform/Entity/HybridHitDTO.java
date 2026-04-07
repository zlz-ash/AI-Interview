package com.ash.springai.interview_platform.Entity;

public record HybridHitDTO(
    String chunkId,
    Long knowledgeBaseId,
    String source,
    double score,
    String highlight,
    String rankReason
) {}
