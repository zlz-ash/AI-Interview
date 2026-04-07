package com.ash.springai.interview_platform.Entity;

import java.util.List;
import java.util.Map;

public record LegacyCleanupResultDTO(
    List<Long> deletedKnowledgeBaseIds,
    List<String> auditRecords,
    Map<Long, String> failures
) {}
