package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;

@Service
public class QueryIntentClassifier {

    public QueryIntent classify(String question) {
        String q = question == null ? "" : question.trim();
        if (q.isBlank()) {
            return new QueryIntent(0.50, 0.50, "BALANCED");
        }

        boolean naturalQuestion = q.contains("为什么") || q.contains("怎么")
            || q.contains("如何") || q.contains("区别")
            || q.contains("什么") || q.contains("哪些") || q.contains("哪个") || q.contains("哪里")
            || q.contains("是否") || q.contains("能否") || q.contains("有哪些")
            || q.contains("介绍") || q.contains("解释") || q.contains("说明")
            || q.contains("告诉我") || q.contains("请问")
            || q.endsWith("?") || q.endsWith("？");
        if (naturalQuestion) {
            return new QueryIntent(0.75, 0.25, "SEMANTIC");
        }

        boolean keywordLike = q.matches(".*[A-Z_]{2,}.*")
            || q.matches(".*\\d+\\.\\d+.*")
            || q.contains("\"");
        if (keywordLike) {
            return new QueryIntent(0.30, 0.70, "KEYWORD");
        }

        return new QueryIntent(0.55, 0.45, "BALANCED");
    }

    public record QueryIntent(double semanticWeight, double keywordWeight, String label) {}
}
