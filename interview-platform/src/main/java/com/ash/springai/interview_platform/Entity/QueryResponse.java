package com.ash.springai.interview_platform.Entity;

public record QueryResponse(
    String answer,
    Long knowledgeBaseId,
    String knowledgeBaseName
) {}
