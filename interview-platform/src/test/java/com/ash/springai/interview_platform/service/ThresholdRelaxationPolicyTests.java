package com.ash.springai.interview_platform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThresholdRelaxationPolicyTests {

    @Test
    void shouldRelaxThresholdWhenHitsNotEnough() {
        ThresholdRelaxationPolicy policy = new ThresholdRelaxationPolicy(5, 3, 0.12, 0.05);
        ThresholdRelaxationPolicy.RelaxationState state = policy.initial(0.30, 0.12, 20, 20);
        ThresholdRelaxationPolicy.RelaxationState next = policy.next(state, 2);
        assertEquals(0.26, next.vecMinScore(), 1e-6);
        assertEquals(0.10, next.ftsMinRank(), 1e-6);
        assertEquals(2, next.round());
    }
}
