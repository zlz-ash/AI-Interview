package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;

import com.ash.springai.interview_platform.mapper.InterviewMapper;
import com.ash.springai.interview_platform.Entity.InterviewDetailDTO;
import com.ash.springai.interview_platform.Entity.InterviewSessionEntity;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;
import com.ash.springai.interview_platform.Entity.InterviewQuestionDTO;
import com.ash.springai.interview_platform.Entity.InterviewAnswerEntity;

import java.util.Optional;
import java.util.List;
import tools.jackson.core.type.TypeReference;


@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewHistoryService {
    
    private final InterviewPersistenceService interviewPersistenceService;
    private final PdfExportService pdfExportService;
    private final ObjectMapper objectMapper;
    private final InterviewMapper interviewMapper;

    public InterviewDetailDTO getInterviewDetail(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = interviewPersistenceService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewSessionEntity session = sessionOpt.get();

        // 解析JSON字段
        List<Object> questions = parseJson(session.getQuestionsJson(), new TypeReference<>() {});
        List<String> strengths = parseJson(session.getStrengthsJson(), new TypeReference<>() {});
        List<String> improvements = parseJson(session.getImprovementsJson(), new TypeReference<>() {});
        List<Object> referenceAnswers = parseJson(session.getReferenceAnswersJson(), new TypeReference<>() {});

        // 解析所有题目（用于构建完整的答案列表）
        List<InterviewQuestionDTO> allQuestions = parseJson(
            session.getQuestionsJson(),
                new TypeReference<>() {
                }
        );

        // 构建答案详情列表（包含所有题目，未回答的也要显示）
        List<InterviewDetailDTO.AnswerDetailDTO> answerList = buildAnswerDetailList(
            allQuestions,
            session.getAnswers()
        );

        // 使用 MapStruct 组装最终 DTO
        return interviewMapper.toDetailDTO(
            session,
            questions,
            strengths,
            improvements,
            referenceAnswers,
            answerList
        );
    }

    private List<InterviewDetailDTO.AnswerDetailDTO> buildAnswerDetailList(
        List<InterviewQuestionDTO> allQuestions,
        List<InterviewAnswerEntity> answers
    ) {
        if (allQuestions == null || allQuestions.isEmpty()) {
            // 如果没有题目数据，回退到仅显示已回答的题目
            return interviewMapper.toAnswerDetailDTOList(answers, this::extractKeyPoints);
        }

        // 将答案按 questionIndex 索引
        java.util.Map<Integer, InterviewAnswerEntity> answerMap = answers.stream()
            .collect(java.util.stream.Collectors.toMap(
                InterviewAnswerEntity::getQuestionIndex,
                a -> a,
                (a1, a2) -> a1  // 如果有重复，取第一个
            ));

        // 遍历所有题目，构建完整的答案详情列表
        return allQuestions.stream()
            .map(question -> {
                InterviewAnswerEntity answer = answerMap.get(question.questionIndex());
                if (answer != null) {
                    // 用户已回答，使用答案数据
                    return interviewMapper.toAnswerDetailDTO(answer, extractKeyPoints(answer));
                } else {
                    // 用户未回答，构建空答案
                    return new InterviewDetailDTO.AnswerDetailDTO(
                        question.questionIndex(),
                        question.question(),
                        question.category(),
                        null,  // userAnswer
                        question.score() != null ? question.score() : 0,  // score
                        question.feedback(),  // feedback
                        null,  // referenceAnswer
                        null,  // keyPoints
                        null   // answeredAt
                    );
                }
            })
            .toList();
    }

    private List<String> extractKeyPoints(InterviewAnswerEntity answer) {
        return parseJson(answer.getKeyPointsJson(), new TypeReference<>() {});
    }

    private <T> T parseJson(String json, TypeReference<T> typeRef) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JacksonException e) {
            log.error("解析 JSON 失败", e);
            return null;
        }
    }

    public byte[] exportInterviewPdf(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = interviewPersistenceService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewSessionEntity session = sessionOpt.get();
        try {
            return pdfExportService.exportInterviewReport(session);
        } catch (Exception e) {
            log.error("导出PDF失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出PDF失败: " + e.getMessage());
        }
    }
}
