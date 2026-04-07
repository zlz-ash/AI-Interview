package com.ash.springai.interview_platform.Entity;

import java.util.List;

public record QueryResponse(
    String answer,
    Long knowledgeBaseId,
    String knowledgeBaseName,
    String mode,
    List<HybridHitDTO> hits,
    HybridMetaDTO meta
) {
    public QueryResponse(String answer, Long knowledgeBaseId, String knowledgeBaseName) {
        this(answer, knowledgeBaseId, knowledgeBaseName, null, List.of(), null);
    }

    public static QueryResponse withSearch(
        String answer,
        Long knowledgeBaseId,
        String knowledgeBaseName,
        String mode,
        List<HybridHitDTO> hits,
        HybridMetaDTO meta
    ) {
        return new QueryResponse(answer, knowledgeBaseId, knowledgeBaseName, mode, hits, meta);
    }
}
