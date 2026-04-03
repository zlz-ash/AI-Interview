package com.ash.springai.interview_platform.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnswerEvaluationBatchingPolicyTests {

    @Test
    void shouldCreateExpectedBatchPlanForTypicalQuestionCounts() throws Exception {
        Object policy = newPolicy(4, 8, 8, 6, 15, 30);

        assertBatchPlan(policy, 5, 5, 1);
        assertBatchPlan(policy, 12, 6, 2);
        assertBatchPlan(policy, 16, 6, 3);
        assertBatchPlan(policy, 31, 8, 4);
    }

    @Test
    void shouldKeepBatchSizeCapForLargeQuestionSet() throws Exception {
        Object policy = newPolicy(4, 8, 3, 6, 15, 30);
        assertBatchPlan(policy, 80, 8, 10);
    }

    private Object newPolicy(
        int minBatchSize,
        int maxBatchSize,
        int maxBatchCount,
        int singleBatchUpperBound,
        int twoBatchUpperBound,
        int largeQuestionUpperBound
    ) throws Exception {
        Class<?> policyClass = Class.forName(
            "com.ash.springai.interview_platform.service.AnswerEvaluationService$EvaluationBatchingPolicy"
        );
        Constructor<?> constructor = policyClass.getDeclaredConstructor(
            int.class, int.class, int.class, int.class, int.class, int.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
            minBatchSize,
            maxBatchSize,
            maxBatchCount,
            singleBatchUpperBound,
            twoBatchUpperBound,
            largeQuestionUpperBound
        );
    }

    private void assertBatchPlan(Object policy, int totalQuestions, int expectedBatchSize, int expectedBatchCount)
        throws Exception {
        Method planMethod = policy.getClass().getDeclaredMethod("plan", int.class);
        planMethod.setAccessible(true);
        Object plan = planMethod.invoke(policy, totalQuestions);

        Method batchSizeMethod = plan.getClass().getDeclaredMethod("batchSize");
        Method batchCountMethod = plan.getClass().getDeclaredMethod("batchCount");
        batchSizeMethod.setAccessible(true);
        batchCountMethod.setAccessible(true);

        int batchSize = (int) batchSizeMethod.invoke(plan);
        int batchCount = (int) batchCountMethod.invoke(plan);
        assertEquals(expectedBatchSize, batchSize);
        assertEquals(expectedBatchCount, batchCount);
    }
}

