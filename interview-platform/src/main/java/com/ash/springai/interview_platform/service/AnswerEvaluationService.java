package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;

import com.ash.springai.interview_platform.common.StructuredOutputInvoker;
import com.ash.springai.interview_platform.Entity.InterviewReportDTO.QuestionEvaluation;
import com.ash.springai.interview_platform.Entity.InterviewReportDTO.ReferenceAnswer;
import com.ash.springai.interview_platform.Entity.InterviewReportDTO.CategoryScore;
import com.ash.springai.interview_platform.Entity.InterviewReportDTO;
import com.ash.springai.interview_platform.Entity.InterviewQuestionDTO;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AnswerEvaluationService {
    
    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationService.class);

    private final ChatClient chatClient;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<EvaluationReportDTO> outputConverter;
    private final PromptTemplate summarySystemPromptTemplate;
    private final PromptTemplate summaryUserPromptTemplate;
    private final BeanOutputConverter<FinalSummaryDTO> summaryOutputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final EvaluationBatchingPolicy batchingPolicy;

    private record EvaluationReportDTO(
        int overallScore,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<QuestionEvaluationDTO> questionEvaluations
    ) {}

    private record QuestionEvaluationDTO(
        int questionIndex,
        int score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints
    ) {}

    private record BatchEvaluationResult(
        int batchNo,
        int startIndex,
        int endIndex,
        List<Integer> expectedQuestionIndexes,
        EvaluationReportDTO report
    ) {}

    private record FinalSummaryDTO(
        String overallFeedback,
        List<String> strengths,
        List<String> improvements
    ) {}

    private record BatchPlan(int batchSize, int batchCount) {}

    private static final class EvaluationBatchingPolicy {
        private final int minBatchSize;
        private final int maxBatchSize;
        private final int maxBatchCount;
        private final int singleBatchUpperBound;
        private final int twoBatchUpperBound;
        private final int largeQuestionUpperBound;

        private EvaluationBatchingPolicy(
            int minBatchSize,
            int maxBatchSize,
            int maxBatchCount,
            int singleBatchUpperBound,
            int twoBatchUpperBound,
            int largeQuestionUpperBound
        ) {
            this.minBatchSize = Math.max(1, minBatchSize);
            this.maxBatchSize = Math.max(this.minBatchSize, maxBatchSize);
            this.maxBatchCount = Math.max(1, maxBatchCount);
            this.singleBatchUpperBound = Math.max(1, singleBatchUpperBound);
            this.twoBatchUpperBound = Math.max(this.singleBatchUpperBound, twoBatchUpperBound);
            this.largeQuestionUpperBound = Math.max(this.twoBatchUpperBound, largeQuestionUpperBound);
        }

        private BatchPlan plan(int totalQuestions) {
            if (totalQuestions <= 0) {
                return new BatchPlan(minBatchSize, 0);
            }

            int targetBatchCount;
            if (totalQuestions <= singleBatchUpperBound) {
                targetBatchCount = 1;
            } else if (totalQuestions <= twoBatchUpperBound) {
                targetBatchCount = 2;
            } else if (totalQuestions <= largeQuestionUpperBound) {
                targetBatchCount = Math.max(3, ceilDiv(totalQuestions, maxBatchSize));
            } else {
                targetBatchCount = ceilDiv(totalQuestions, maxBatchSize);
            }

            targetBatchCount = Math.min(Math.max(1, targetBatchCount), maxBatchCount);
            int batchSize = ceilDiv(totalQuestions, targetBatchCount);
            batchSize = Math.max(minBatchSize, Math.min(maxBatchSize, batchSize));
            int actualBatchCount = ceilDiv(totalQuestions, batchSize);
            return new BatchPlan(batchSize, actualBatchCount);
        }

        private int ceilDiv(int numerator, int denominator) {
            return (numerator + denominator - 1) / denominator;
        }
    }

    public AnswerEvaluationService(
            ChatClient.Builder chatClientBuilder,
            StructuredOutputInvoker structuredOutputInvoker,
            @Value("classpath:prompts/interview-evaluation-system.st") Resource systemPromptResource,
            @Value("classpath:prompts/interview-evaluation-user.st") Resource userPromptResource,
            @Value("classpath:prompts/interview-evaluation-summary-system.st") Resource summarySystemPromptResource,
            @Value("classpath:prompts/interview-evaluation-summary-user.st") Resource summaryUserPromptResource,
            @Value("${app.interview.evaluation.batch.min-size:4}") int minBatchSize,
            @Value("${app.interview.evaluation.batch.max-size:8}") int maxBatchSize,
            @Value("${app.interview.evaluation.batch.max-batches:8}") int maxBatchCount,
            @Value("${app.interview.evaluation.batch.single-upper-bound:6}") int singleBatchUpperBound,
            @Value("${app.interview.evaluation.batch.double-upper-bound:15}") int twoBatchUpperBound,
            @Value("${app.interview.evaluation.batch.large-upper-bound:30}") int largeQuestionUpperBound) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.outputConverter = new BeanOutputConverter<>(EvaluationReportDTO.class);
        this.summarySystemPromptTemplate = new PromptTemplate(summarySystemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.summaryUserPromptTemplate = new PromptTemplate(summaryUserPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.summaryOutputConverter = new BeanOutputConverter<>(FinalSummaryDTO.class);
        this.batchingPolicy = new EvaluationBatchingPolicy(
            minBatchSize,
            maxBatchSize,
            maxBatchCount,
            singleBatchUpperBound,
            twoBatchUpperBound,
            largeQuestionUpperBound
        );
    }

    public InterviewReportDTO evaluateInterview(String sessionId, String resumeText,
                List<InterviewQuestionDTO> questions) {
        log.info("开始评估面试: {}, 共{}题", sessionId, questions.size());

        try {
            // 简历摘要（限制长度）
            String resumeSummary = resumeText.length() > 500 
                ? resumeText.substring(0, 500) + "..." 
                : resumeText;

            // 分批评估，避免单次上下文过大导致 token 超限
            List<BatchEvaluationResult> batchResults = evaluateInBatches(sessionId, resumeSummary, questions);

            List<QuestionEvaluationDTO> mergedEvaluations = mergeQuestionEvaluations(batchResults, questions);
            String fallbackOverallFeedback = mergeOverallFeedback(batchResults);
            List<String> fallbackStrengths = mergeListItems(batchResults, true);
            List<String> fallbackImprovements = mergeListItems(batchResults, false);
            FinalSummaryDTO finalSummary = summarizeBatchResults(
                sessionId,
                resumeSummary,
                questions,
                mergedEvaluations,
                fallbackOverallFeedback,
                fallbackStrengths,
                fallbackImprovements
            );

            // 转换为业务对象
            return convertToReport(
                sessionId,
                mergedEvaluations,
                questions,
                finalSummary.overallFeedback(),
                finalSummary.strengths(),
                finalSummary.improvements()
            );

        } catch (BusinessException e) {
        // 重新抛出业务异常
            throw e;
        } catch (Exception e) {
            log.error("面试评估失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, 
            "面试评估失败：" + e.getMessage());
        }
    }

    private String buildQARecords(List<InterviewQuestionDTO> questions) {
        StringBuilder sb = new StringBuilder();
        for (InterviewQuestionDTO q : questions) {
            sb.append(String.format("问题%d [%s]: %s\n", 
                q.questionIndex() + 1, q.category(), q.question()));
            sb.append(String.format("回答: %s\n\n", 
                q.userAnswer() != null ? q.userAnswer() : "(未回答)"));
        }
        return sb.toString();
    }

    private List<BatchEvaluationResult> evaluateInBatches(
        String sessionId,
        String resumeSummary,
        List<InterviewQuestionDTO> questions
    ) {
        List<BatchEvaluationResult> results = new ArrayList<>();
        BatchPlan batchPlan = batchingPolicy.plan(questions.size());
        int batchSize = batchPlan.batchSize();
        log.info("评估分批计划: sessionId={}, totalQuestions={}, batchSize={}, batchCount={}",
            sessionId, questions.size(), batchSize, batchPlan.batchCount());

        int batchNo = 1;
        for (int start = 0; start < questions.size(); start += batchSize) {
            int end = Math.min(start + batchSize, questions.size());
            List<InterviewQuestionDTO> batchQuestions = questions.subList(start, end);
            long beginNs = System.nanoTime();
            EvaluationReportDTO report = evaluateBatch(sessionId, resumeSummary, batchQuestions, start, end, batchNo);
            long durationMs = (System.nanoTime() - beginNs) / 1_000_000;
            List<Integer> expectedIndexes = batchQuestions.stream()
                .map(InterviewQuestionDTO::questionIndex)
                .toList();
            results.add(new BatchEvaluationResult(batchNo, start, end, expectedIndexes, report));
            log.debug("批次处理完成: sessionId={}, batchNo={}, range=[{}, {}), expectedIndexes={}, durationMs={}",
                sessionId, batchNo, start, end, expectedIndexes, durationMs);
            batchNo++;
        }
        return results;
    }

    private EvaluationReportDTO evaluateBatch(
        String sessionId,
        String resumeSummary,
        List<InterviewQuestionDTO> batchQuestions,
        int start,
        int end,
        int batchNo
    ) {
        String qaRecords = buildQARecords(batchQuestions);
        String systemPrompt = systemPromptTemplate.render();

        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeSummary);
        variables.put("qaRecords", qaRecords);
        String userPrompt = userPromptTemplate.render(variables);

        String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
        try {
            EvaluationReportDTO dto = structuredOutputInvoker.invoke(
                chatClient,
                systemPromptWithFormat,
                userPrompt,
                outputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试评估失败：",
                "批次评估",
                log
            );
            log.debug("批次评估完成: sessionId={}, batchNo={}, range=[{}, {}), batchSize={}",
                sessionId, batchNo, start, end, batchQuestions.size());
            return dto;
        } catch (Exception e) {
            log.error("批次评估失败: sessionId={}, batchNo={}, range=[{}, {}), error={}",
                sessionId, batchNo, start, end, e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "面试评估失败：" + e.getMessage());
        }
    }

    private List<QuestionEvaluationDTO> mergeQuestionEvaluations(
        List<BatchEvaluationResult> batchResults,
        List<InterviewQuestionDTO> questions
    ) {
        Map<Integer, QuestionEvaluationDTO> mergedByQuestionIndex = new HashMap<>();
        for (BatchEvaluationResult result : batchResults) {
            List<Integer> expectedIndexes = result.expectedQuestionIndexes();
            Set<Integer> filledIndexes = new HashSet<>();
            List<QuestionEvaluationDTO> current =
                result.report() != null && result.report().questionEvaluations() != null
                    ? result.report().questionEvaluations()
                    : List.of();
            for (int i = 0; i < current.size(); i++) {
                QuestionEvaluationDTO item = current.get(i);
                if (item == null) {
                    continue;
                }
                int normalizedIndex = normalizeQuestionIndex(item.questionIndex(), expectedIndexes, i);
                mergedByQuestionIndex.put(normalizedIndex, new QuestionEvaluationDTO(
                    normalizedIndex,
                    item.score(),
                    item.feedback(),
                    item.referenceAnswer(),
                    item.keyPoints() != null ? item.keyPoints() : List.of()
                ));
                filledIndexes.add(normalizedIndex);
            }

            for (int expectedIndex : expectedIndexes) {
                if (filledIndexes.contains(expectedIndex)) {
                    continue;
                }
                mergedByQuestionIndex.put(expectedIndex, new QuestionEvaluationDTO(
                    expectedIndex,
                    0,
                    "该题未成功生成评估结果，系统按 0 分处理。",
                    "",
                    List.of()
                ));
            }
        }

        List<QuestionEvaluationDTO> merged = new ArrayList<>();
        for (InterviewQuestionDTO question : questions) {
            int questionIndex = question.questionIndex();
            merged.add(mergedByQuestionIndex.getOrDefault(
                questionIndex,
                new QuestionEvaluationDTO(
                    questionIndex,
                    0,
                    "该题未成功生成评估结果，系统按 0 分处理。",
                    "",
                    List.of()
                )
            ));
        }
        return merged;
    }

    private int normalizeQuestionIndex(int modelIndex, List<Integer> expectedIndexes, int fallbackPosition) {
        if (expectedIndexes.contains(modelIndex)) {
            return modelIndex;
        }
        int zeroBasedCandidate = modelIndex - 1;
        if (expectedIndexes.contains(zeroBasedCandidate)) {
            return zeroBasedCandidate;
        }
        if (fallbackPosition >= 0 && fallbackPosition < expectedIndexes.size()) {
            return expectedIndexes.get(fallbackPosition);
        }
        return expectedIndexes.isEmpty() ? modelIndex : expectedIndexes.get(0);
    }

    private String mergeOverallFeedback(List<BatchEvaluationResult> batchResults) {
        String feedback = batchResults.stream()
            .map(BatchEvaluationResult::report)
            .filter(r -> r != null && r.overallFeedback() != null && !r.overallFeedback().isBlank())
            .map(EvaluationReportDTO::overallFeedback)
            .collect(Collectors.joining("\n\n"));
        if (!feedback.isBlank()) {
            return feedback;
        }
        return "本次面试已完成分批评估，但未生成有效综合评语。";
    }

    private List<String> mergeListItems(List<BatchEvaluationResult> batchResults, boolean strengthsMode) {
        Set<String> merged = new LinkedHashSet<>();
        for (BatchEvaluationResult result : batchResults) {
            EvaluationReportDTO report = result.report();
            if (report == null) {
                continue;
            }
            List<String> items = strengthsMode ? report.strengths() : report.improvements();
            if (items == null) {
                continue;
            }
            items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .forEach(merged::add);
        }
        return merged.stream().limit(8).toList();
    }

    private FinalSummaryDTO summarizeBatchResults(
        String sessionId,
        String resumeSummary,
        List<InterviewQuestionDTO> questions,
        List<QuestionEvaluationDTO> evaluations,
        String fallbackOverallFeedback,
        List<String> fallbackStrengths,
        List<String> fallbackImprovements
    ) {
        try {
            String summarySystemPrompt = summarySystemPromptTemplate.render();
            Map<String, Object> variables = new HashMap<>();
            variables.put("resumeText", resumeSummary);
            variables.put("categorySummary", buildCategorySummary(questions, evaluations));
            variables.put("questionHighlights", buildQuestionHighlights(questions, evaluations));
            variables.put("fallbackOverallFeedback", fallbackOverallFeedback);
            variables.put("fallbackStrengths", String.join("\n", fallbackStrengths));
            variables.put("fallbackImprovements", String.join("\n", fallbackImprovements));
            String summaryUserPrompt = summaryUserPromptTemplate.render(variables);

            String systemPromptWithFormat = summarySystemPrompt + "\n\n" + summaryOutputConverter.getFormat();
            FinalSummaryDTO dto = structuredOutputInvoker.invoke(
                chatClient,
                systemPromptWithFormat,
                summaryUserPrompt,
                summaryOutputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试总结失败：",
                "总结评估",
                log
            );

            String overallFeedback = dto != null && dto.overallFeedback() != null && !dto.overallFeedback().isBlank()
                ? dto.overallFeedback()
                : fallbackOverallFeedback;
            List<String> strengths = sanitizeSummaryItems(
                dto != null ? dto.strengths() : null,
                fallbackStrengths
            );
            List<String> improvements = sanitizeSummaryItems(
                dto != null ? dto.improvements() : null,
                fallbackImprovements
            );

            log.debug("二次汇总评估完成: sessionId={}", sessionId);
            return new FinalSummaryDTO(overallFeedback, strengths, improvements);
        } catch (Exception e) {
            log.warn("二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}", sessionId, e.getMessage());
            return new FinalSummaryDTO(
                fallbackOverallFeedback,
                fallbackStrengths,
                fallbackImprovements
            );
        }
    }

    private List<String> sanitizeSummaryItems(List<String> primary, List<String> fallback) {
        List<String> source = (primary != null && !primary.isEmpty()) ? primary : fallback;
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim)
            .distinct()
            .limit(8)
            .toList();
    }

    private String buildCategorySummary(List<InterviewQuestionDTO> questions, List<QuestionEvaluationDTO> evaluations) {
        Map<String, List<Integer>> categoryScores = new HashMap<>();
        for (int i = 0; i < questions.size(); i++) {
            InterviewQuestionDTO q = questions.get(i);
            QuestionEvaluationDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = 0;
            if (eval != null && q.userAnswer() != null && !q.userAnswer().isBlank()) {
                score = eval.score();
            }
            categoryScores.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }

        return categoryScores.entrySet().stream()
            .map(entry -> {
                int count = entry.getValue().size();
                int avg = (int) entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
                return String.format("- %s: 平均分 %d, 题数 %d", entry.getKey(), avg, count);
            })
            .sorted()
            .collect(Collectors.joining("\n"));
    }

    private String buildQuestionHighlights(List<InterviewQuestionDTO> questions, List<QuestionEvaluationDTO> evaluations) {
        List<String> highlights = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            InterviewQuestionDTO q = questions.get(i);
            QuestionEvaluationDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = eval != null ? eval.score() : 0;
            String feedback = eval != null && eval.feedback() != null ? eval.feedback() : "";
            String questionText = q.question() != null ? q.question() : "";
            String shortQuestion = questionText.length() > 50 ? questionText.substring(0, 50) + "..." : questionText;
            String shortFeedback = feedback.length() > 80 ? feedback.substring(0, 80) + "..." : feedback;
            highlights.add(String.format("- Q%d | %s | 分数:%d | 反馈:%s",
                q.questionIndex() + 1, shortQuestion, score, shortFeedback));
        }
        return highlights.stream().limit(20).collect(Collectors.joining("\n"));
    }
    
    /**
     * 转换DTO为业务对象
     */
    private InterviewReportDTO convertToReport(
        String sessionId,
        List<QuestionEvaluationDTO> evaluations,
        List<InterviewQuestionDTO> questions,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements
    ) {
        List<QuestionEvaluation> questionDetails = new ArrayList<>();
        List<ReferenceAnswer> referenceAnswers = new ArrayList<>();
        Map<String, List<Integer>> categoryScoresMap = new HashMap<>();

        // 统计实际回答的问题数量
        long answeredCount = questions.stream()
            .filter(q -> q.userAnswer() != null && !q.userAnswer().isBlank())
            .count();

        // 处理问题评估（防御性编程：AI 响应解析后可能为 null）
        int evaluationsSize = evaluations != null ? evaluations.size() : 0;
        Map<Integer, QuestionEvaluationDTO> evaluationMap = new HashMap<>();
        if (evaluations != null) {
            for (QuestionEvaluationDTO eval : evaluations) {
                if (eval != null) {
                    evaluationMap.put(eval.questionIndex(), eval);
                }
            }
        }
        if (evaluations == null || evaluations.isEmpty()) {
            log.warn("面试评估结果解析异常：问题评估列表为空，sessionId={}", sessionId);
        }
        for (int i = 0; i < questions.size(); i++) {
            InterviewQuestionDTO q = questions.get(i);
            int qIndex = q.questionIndex();
            QuestionEvaluationDTO eval = evaluationMap.get(qIndex);
            if (eval == null && i < evaluationsSize) {
                eval = evaluations.get(i);
            }
            String feedback = eval != null && eval.feedback() != null
                ? eval.feedback()
                : "该题未成功生成评估反馈。";
            String referenceAnswer = eval != null && eval.referenceAnswer() != null
                ? eval.referenceAnswer()
                : "";
            List<String> keyPoints = eval != null && eval.keyPoints() != null
                ? eval.keyPoints()
                : List.of();

            // 如果用户未回答该题，分数强制为 0
            boolean hasAnswer = q.userAnswer() != null && !q.userAnswer().isBlank();
            int score = hasAnswer && eval != null ? eval.score() : 0;

            questionDetails.add(new QuestionEvaluation(
                qIndex, q.question(), q.category(),
                q.userAnswer(), score, feedback
            ));

            referenceAnswers.add(new ReferenceAnswer(
                qIndex, q.question(),
                referenceAnswer,
                keyPoints
            ));

            // 收集类别分数
            categoryScoresMap
                .computeIfAbsent(q.category(), k -> new ArrayList<>())
                .add(score);
        }

        // 计算各类别平均分
        List<CategoryScore> categoryScores = categoryScoresMap.entrySet().stream()
            .map(e -> new CategoryScore(
                e.getKey(),
                (int) e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                e.getValue().size()
            ))
            .collect(Collectors.toList());

        // 计算总分：基于实际得分，而非 AI 返回值
        // 如果所有问题都未回答，总分为 0
        int overallScore;
        if (answeredCount == 0) {
            overallScore = 0;
        } else {
            // 使用问题详情中的分数计算平均值
            overallScore = (int) questionDetails.stream()
                .mapToInt(QuestionEvaluation::score)
                .average()
                .orElse(0);
        }

        return new InterviewReportDTO(
            sessionId,
            questions.size(),
            overallScore,
            categoryScores,
            questionDetails,
            overallFeedback,
            strengths != null ? strengths : List.of(),
            improvements != null ? improvements : List.of(),
            referenceAnswers
        );
    }
}
