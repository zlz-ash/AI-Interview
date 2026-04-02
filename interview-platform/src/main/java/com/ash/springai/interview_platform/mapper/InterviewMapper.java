package com.ash.springai.interview_platform.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

import com.ash.springai.interview_platform.Entity.InterviewAnswerEntity;
import com.ash.springai.interview_platform.Entity.InterviewReportDTO;
import com.ash.springai.interview_platform.Entity.InterviewDetailDTO;
import com.ash.springai.interview_platform.Entity.InterviewSessionEntity;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface InterviewMapper {
    
    @Mapping(target = "questionIndex", source = "questionIndex", qualifiedByName = "nullIndexToZero")
    @Mapping(target = "question", source = "question")
    @Mapping(target = "category", source = "category")
    @Mapping(target = "userAnswer", source = "userAnswer")
    @Mapping(target = "score", source = "score", qualifiedByName = "nullScoreToZero")
    @Mapping(target = "feedback", source = "feedback")
    InterviewReportDTO.QuestionEvaluation toQuestionEvaluation(InterviewAnswerEntity entity);

    List<InterviewReportDTO.QuestionEvaluation> toQuestionEvaluations(List<InterviewAnswerEntity> entities);

    @Mapping(target = "keyPoints", source = "keyPoints")
    InterviewDetailDTO.AnswerDetailDTO toAnswerDetailDTO(
        InterviewAnswerEntity entity,
        List<String> keyPoints
    );

    default List<InterviewDetailDTO.AnswerDetailDTO> toAnswerDetailDTOList(
        List<InterviewAnswerEntity> entities,
        java.util.function.Function<InterviewAnswerEntity, List<String>> keyPointsExtractor
    ) {
        return entities.stream()
            .map(e -> toAnswerDetailDTO(e, keyPointsExtractor.apply(e)))
            .toList();
    }

    @Mapping(target = "status", expression = "java(session.getStatus().toString())")
    @Mapping(target = "evaluateStatus", expression = "java(session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null)")
    @Mapping(target = "evaluateError", source = "session.evaluateError")
    @Mapping(target = "questions", source = "questions")
    @Mapping(target = "strengths", source = "strengths")
    @Mapping(target = "improvements", source = "improvements")
    @Mapping(target = "referenceAnswers", source = "referenceAnswers")
    @Mapping(target = "answers", source = "answers")
    InterviewDetailDTO toDetailDTO(
        InterviewSessionEntity session,
        List<Object> questions,
        List<String> strengths,
        List<String> improvements,
        List<Object> referenceAnswers,
        List<InterviewDetailDTO.AnswerDetailDTO> answers
    );

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "sessionId", ignore = true)
    @Mapping(target = "resume", ignore = true)
    @Mapping(target = "totalQuestions", ignore = true)
    @Mapping(target = "currentQuestionIndex", ignore = true)
    @Mapping(target = "questionsJson", ignore = true)
    @Mapping(target = "strengthsJson", ignore = true)
    @Mapping(target = "improvementsJson", ignore = true)
    @Mapping(target = "referenceAnswersJson", ignore = true)
    @Mapping(target = "answers", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "completedAt", ignore = true)
    void updateSessionFromReport(InterviewReportDTO report, @MappingTarget InterviewSessionEntity session);

    default java.util.Map<String, Object> toInterviewHistoryItem(InterviewSessionEntity session) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", session.getId());
        map.put("sessionId", session.getSessionId());
        map.put("totalQuestions", session.getTotalQuestions());
        map.put("status", session.getStatus().toString());
        map.put("evaluateStatus", session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null);
        map.put("evaluateError", session.getEvaluateError());
        map.put("overallScore", session.getOverallScore());
        map.put("createdAt", session.getCreatedAt());
        map.put("completedAt", session.getCompletedAt());
        return map;
    }

    default List<Object> toInterviewHistoryList(List<InterviewSessionEntity> sessions) {
        return sessions.stream()
            .map(this::toInterviewHistoryItem)
            .map(m -> (Object) m)
            .toList();
    }

    @Named("nullIndexToZero")
    default int nullIndexToZero(Integer value) {
        return value != null ? value : 0;
    }

    @Named("nullScoreToZero")
    default int nullScoreToZero(Integer value) {
        return value != null ? value : 0;
    }
}
