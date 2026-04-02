package com.ash.springai.interview_platform.Entity;

import java.time.LocalDateTime;

public record ResumeListItemDTO (
    Long id,
    String filename,
    Long fileSize,
    LocalDateTime uploadedAt,
    Integer accessCount,
    Integer latestScore,
    LocalDateTime lastAnalyzedAt,
    Integer interviewCount){
    
}
