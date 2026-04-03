package com.ash.springai.interview_platform.service;

import com.ash.springai.interview_platform.Entity.InterviewQuestionDTO;
import com.ash.springai.interview_platform.Entity.InterviewQuestionDTO.QuestionType;
import com.ash.springai.interview_platform.common.StructuredOutputInvoker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnswerEvaluationMergeFallbackTests {

    @Test
    @DisplayName("当批次评估缺少题目时，应自动补齐0分占位结果")
    void shouldFillMissingQuestionsWithZeroScoreFallback() throws Exception {
        AnswerEvaluationService service = newService();

        Object eval0 = newQuestionEvaluation(0, 82, "回答较完整", "ref0", List.of("k0"));
        Object report = newEvaluationReport(
            82,
            "batch overall",
            List.of("s1"),
            List.of("i1"),
            List.of(eval0)
        );
        Object batchResult = newBatchEvaluationResult(1, 0, 2, List.of(0, 1), report);

        List<InterviewQuestionDTO> questions = List.of(
            question(0, "Q0"),
            question(1, "Q1")
        );

        List<?> merged = mergeQuestionEvaluations(service, List.of(batchResult), questions);

        assertEquals(2, merged.size());
        assertAll(
            () -> assertEquals(0, invokeInt(merged.get(0), "questionIndex")),
            () -> assertEquals(82, invokeInt(merged.get(0), "score")),
            () -> assertEquals(1, invokeInt(merged.get(1), "questionIndex")),
            () -> assertEquals(0, invokeInt(merged.get(1), "score")),
            () -> assertEquals(
                "该题未成功生成评估结果，系统按 0 分处理。",
                invokeString(merged.get(1), "feedback")
            )
        );
    }

    @Test
    @DisplayName("当模型返回异常索引时，应按批次位置回填到期望题号")
    void shouldFallbackToExpectedIndexWhenModelIndexCannotBeMatched() throws Exception {
        AnswerEvaluationService service = newService();

        Object evalA = newQuestionEvaluation(101, 70, "A", "refA", List.of("ka"));
        Object evalB = newQuestionEvaluation(102, 60, "B", "refB", List.of("kb"));
        Object report = newEvaluationReport(
            65,
            "batch overall",
            List.of(),
            List.of(),
            List.of(evalA, evalB)
        );
        Object batchResult = newBatchEvaluationResult(1, 0, 2, List.of(10, 11), report);

        List<InterviewQuestionDTO> questions = List.of(
            question(10, "Q10"),
            question(11, "Q11")
        );

        List<?> merged = mergeQuestionEvaluations(service, List.of(batchResult), questions);

        assertEquals(2, merged.size());
        assertAll(
            () -> assertEquals(10, invokeInt(merged.get(0), "questionIndex")),
            () -> assertEquals(70, invokeInt(merged.get(0), "score")),
            () -> assertEquals(11, invokeInt(merged.get(1), "questionIndex")),
            () -> assertEquals(60, invokeInt(merged.get(1), "score"))
        );
    }

    private AnswerEvaluationService newService() throws Exception {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(builder.build()).thenReturn(chatClient);

        StructuredOutputInvoker invoker = new StructuredOutputInvoker(1, false);
        Resource system = new ByteArrayResource("sys".getBytes(StandardCharsets.UTF_8));
        Resource user = new ByteArrayResource("user {resumeText} {qaRecords}".getBytes(StandardCharsets.UTF_8));
        Resource summarySystem = new ByteArrayResource("sum".getBytes(StandardCharsets.UTF_8));
        Resource summaryUser = new ByteArrayResource("sum {categorySummary}".getBytes(StandardCharsets.UTF_8));

        return new AnswerEvaluationService(
            builder,
            invoker,
            system,
            user,
            summarySystem,
            summaryUser,
            4,
            8,
            8,
            6,
            15,
            30
        );
    }

    @SuppressWarnings("unchecked")
    private List<?> mergeQuestionEvaluations(
        AnswerEvaluationService service,
        List<Object> batchResults,
        List<InterviewQuestionDTO> questions
    ) throws Exception {
        Method method = AnswerEvaluationService.class.getDeclaredMethod(
            "mergeQuestionEvaluations",
            List.class,
            List.class
        );
        method.setAccessible(true);
        return (List<?>) method.invoke(service, batchResults, questions);
    }

    private Object newQuestionEvaluation(
        int questionIndex,
        int score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints
    ) throws Exception {
        Class<?> clazz = Class.forName(
            "com.ash.springai.interview_platform.service.AnswerEvaluationService$QuestionEvaluationDTO"
        );
        Constructor<?> constructor = clazz.getDeclaredConstructor(
            int.class,
            int.class,
            String.class,
            String.class,
            List.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(questionIndex, score, feedback, referenceAnswer, keyPoints);
    }

    private Object newEvaluationReport(
        int overallScore,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<Object> questionEvaluations
    ) throws Exception {
        Class<?> clazz = Class.forName(
            "com.ash.springai.interview_platform.service.AnswerEvaluationService$EvaluationReportDTO"
        );
        Constructor<?> constructor = clazz.getDeclaredConstructor(
            int.class,
            String.class,
            List.class,
            List.class,
            List.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
            overallScore,
            overallFeedback,
            strengths,
            improvements,
            questionEvaluations
        );
    }

    private Object newBatchEvaluationResult(
        int batchNo,
        int startIndex,
        int endIndex,
        List<Integer> expectedQuestionIndexes,
        Object report
    ) throws Exception {
        Class<?> clazz = Class.forName(
            "com.ash.springai.interview_platform.service.AnswerEvaluationService$BatchEvaluationResult"
        );
        Class<?> reportClazz = Class.forName(
            "com.ash.springai.interview_platform.service.AnswerEvaluationService$EvaluationReportDTO"
        );
        Constructor<?> constructor = clazz.getDeclaredConstructor(
            int.class,
            int.class,
            int.class,
            List.class,
            reportClazz
        );
        constructor.setAccessible(true);
        return constructor.newInstance(batchNo, startIndex, endIndex, expectedQuestionIndexes, report);
    }

    private int invokeInt(Object target, String accessor) throws Exception {
        Method method = target.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return (int) method.invoke(target);
    }

    private String invokeString(Object target, String accessor) throws Exception {
        Method method = target.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return (String) method.invoke(target);
    }

    private InterviewQuestionDTO question(int index, String questionText) {
        return new InterviewQuestionDTO(
            index,
            questionText,
            QuestionType.JAVA_BASIC,
            "Java基础",
            "answer-" + index,
            null,
            null,
            false,
            null
        );
    }
}
