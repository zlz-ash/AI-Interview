package com.ash.springai.interview_platform.service.chunking;

public interface TokenCounter {

    int count(String text);

    String truncateToTokens(String text, int maxTokens);
}
