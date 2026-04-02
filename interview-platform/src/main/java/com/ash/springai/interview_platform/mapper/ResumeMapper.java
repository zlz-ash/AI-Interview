package com.ash.springai.interview_platform.mapper;

import org.mapstruct.*;

import com.ash.springai.interview_platform.Entity.ResumeAnalysisResponse;
import com.ash.springai.interview_platform.Entity.ResumeAnalysisEntity;
import com.ash.springai.interview_platform.Entity.ResumeListItemDTO;
import com.ash.springai.interview_platform.Entity.ResumeEntity;
import com.ash.springai.interview_platform.Entity.ResumeDetailDTO;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ResumeMapper {
    
    @Mapping(target = "contentScore", source = "contentScore", qualifiedByName = "nullToZero")
    @Mapping(target = "structureScore", source = "structureScore", qualifiedByName = "nullToZero")
    @Mapping(target = "skillMatchScore", source = "skillMatchScore", qualifiedByName = "nullToZero")
    @Mapping(target = "expressionScore", source = "expressionScore", qualifiedByName = "nullToZero")
    @Mapping(target = "projectScore", source = "projectScore", qualifiedByName = "nullToZero")
    ResumeAnalysisResponse.ScoreDetail toScoreDetail(ResumeAnalysisEntity entity);

    default ResumeListItemDTO toListItemDTO(
        ResumeEntity resume,
        Integer latestScore,
        java.time.LocalDateTime lastAnalyzedAt,
        Integer interviewCount
    ) {
        return new ResumeListItemDTO(
            resume.getId(),
            resume.getOriginalFilename(),
            resume.getFileSize(),
            resume.getUploadedAt(),
            resume.getAccessCount(),
            latestScore,
            lastAnalyzedAt,
            interviewCount
        );
    }

    @Mapping(target = "filename", source = "originalFilename")
    @Mapping(target = "latestScore", ignore = true)
    @Mapping(target = "lastAnalyzedAt", ignore = true)
    @Mapping(target = "interviewCount", ignore = true)
    ResumeListItemDTO toListItemDTOBasic(ResumeEntity entity);

    @Mapping(target = "filename", source = "originalFilename")
    @Mapping(target = "analyses", ignore = true)
    @Mapping(target = "interviews", ignore = true)
    ResumeDetailDTO toDetailDTOBasic(ResumeEntity entity);

    @Mapping(target = "strengths", source = "strengths")
    @Mapping(target = "suggestions", source = "suggestions")
    ResumeDetailDTO.AnalysisHistoryDTO toAnalysisHistoryDTO(
        ResumeAnalysisEntity entity,
        List<String> strengths,
        List<Object> suggestions
    );

    default List<ResumeDetailDTO.AnalysisHistoryDTO> toAnalysisHistoryDTOList(
        List<ResumeAnalysisEntity> entities,
        java.util.function.Function<ResumeAnalysisEntity, List<String>> strengthsExtractor,
        java.util.function.Function<ResumeAnalysisEntity, List<Object>> suggestionsExtractor
    ) {
        return entities.stream()
            .map(e -> toAnalysisHistoryDTO(e, strengthsExtractor.apply(e), suggestionsExtractor.apply(e)))
            .toList();
    } 

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "resume", ignore = true)
    @Mapping(target = "strengthsJson", ignore = true)
    @Mapping(target = "suggestionsJson", ignore = true)
    @Mapping(target = "analyzedAt", ignore = true)
    @Mapping(target = "contentScore", source = "scoreDetail.contentScore")
    @Mapping(target = "structureScore", source = "scoreDetail.structureScore")
    @Mapping(target = "skillMatchScore", source = "scoreDetail.skillMatchScore")
    @Mapping(target = "expressionScore", source = "scoreDetail.expressionScore")
    @Mapping(target = "projectScore", source = "scoreDetail.projectScore")
    ResumeAnalysisEntity toAnalysisEntity(ResumeAnalysisResponse response);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "resume", ignore = true)
    @Mapping(target = "strengthsJson", ignore = true)
    @Mapping(target = "suggestionsJson", ignore = true)
    @Mapping(target = "analyzedAt", ignore = true)
    @Mapping(target = "contentScore", source = "scoreDetail.contentScore")
    @Mapping(target = "structureScore", source = "scoreDetail.structureScore")
    @Mapping(target = "skillMatchScore", source = "scoreDetail.skillMatchScore")
    @Mapping(target = "expressionScore", source = "scoreDetail.expressionScore")
    @Mapping(target = "projectScore", source = "scoreDetail.projectScore")
    void updateAnalysisEntity(ResumeAnalysisResponse response, @MappingTarget ResumeAnalysisEntity entity);

    @Named("nullToZero")
    default int nullToZero(Integer value) {
        return value != null ? value : 0;
    }
}
