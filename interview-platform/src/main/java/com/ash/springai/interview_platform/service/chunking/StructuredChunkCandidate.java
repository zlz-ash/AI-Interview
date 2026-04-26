package com.ash.springai.interview_platform.service.chunking;

import java.util.Map;

public record StructuredChunkCandidate(
    String sectionPath,
    String heading,
    String body,
    Map<String, Object> metadata
) {}
