package com.ash.springai.interview_platform.service.chunking;

public record ChunkBudgetPolicy(int targetMaxTokens, int overlapTokens) {

    public ChunkBudgetPolicy {
        if (targetMaxTokens < 1) {
            throw new IllegalArgumentException("targetMaxTokens 必须 >= 1");
        }
        if (overlapTokens < 0 || overlapTokens >= targetMaxTokens) {
            throw new IllegalArgumentException("overlapTokens 必须在 [0, targetMaxTokens) 区间");
        }
    }
}
