package com.ash.springai.interview_platform.Entity;

public record HybridMetaDTO(
    int rounds,
    double vecMinScore,
    double ftsMinRank,
    double vectorWeight,
    double ftsWeight,
    boolean lowConfidence
) {}
