package com.ash.springai.interview_platform.Entity;

import java.util.Map;

public record IngestChunkDTO(
    int chunkIndex,
    String content,
    int tokenEstimate,
    Map<String, Object> metadata
) {}
