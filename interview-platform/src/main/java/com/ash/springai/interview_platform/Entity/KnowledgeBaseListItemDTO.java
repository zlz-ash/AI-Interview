package com.ash.springai.interview_platform.Entity;

import java.time.LocalDateTime;

import com.ash.springai.interview_platform.enums.VectorStatus;

public record KnowledgeBaseListItemDTO(
    Long id,
    String name,
    String category,
    String originalFilename,
    Long fileSize,
    String contentType,
    LocalDateTime uploadedAt,
    LocalDateTime lastAccessedAt,
    Integer accessCount,
    Integer questionCount,
    VectorStatus vectorStatus,
    String vectorError,
    Integer chunkCount
) {}
