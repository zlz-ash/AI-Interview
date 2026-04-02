package com.ash.springai.interview_platform.Entity;

import java.time.LocalDateTime;
import java.util.List;

import com.ash.springai.interview_platform.enums.AsyncTaskStatus;

public record ResumeDetailDTO (
    Long id,
    String filename,
    Long fileSize,
    String contentType,
    String storageUrl,
    LocalDateTime uploadedAt,
    Integer accessCount,
    String resumeText,
    AsyncTaskStatus analyzeStatus,
    String analyzeError,
    List<AnalysisHistoryDTO> analyses,
    List<Object> interviews){
    
        public record AnalysisHistoryDTO(
            Long id,
            Integer overallScore,
            Integer contentScore,
            Integer structureScore,
            Integer skillMatchScore,
            Integer expressionScore,
            Integer projectScore,
            String summary,
            LocalDateTime analyzedAt,
            List<String> strengths,
            List<Object> suggestions
        ) {}
}
