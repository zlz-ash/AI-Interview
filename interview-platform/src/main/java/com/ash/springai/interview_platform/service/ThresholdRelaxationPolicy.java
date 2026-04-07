package com.ash.springai.interview_platform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ThresholdRelaxationPolicy {

    private final int minEffectiveHits;
    private final int maxRelaxRounds;
    private final double vecFloor;
    private final double ftsFloor;

    public ThresholdRelaxationPolicy(
        @Value("${app.search.threshold.min-effective-hits:5}") int minEffectiveHits,
        @Value("${app.search.threshold.max-relax-rounds:3}") int maxRelaxRounds,
        @Value("${app.search.threshold.vec-floor:0.12}") double vecFloor,
        @Value("${app.search.threshold.fts-floor:0.05}") double ftsFloor
    ) {
        this.minEffectiveHits = minEffectiveHits;
        this.maxRelaxRounds = maxRelaxRounds;
        this.vecFloor = vecFloor;
        this.ftsFloor = ftsFloor;
    }

    public RelaxationState initial(double vecMinScore, double ftsMinRank, int topKVec, int topKFts) {
        return new RelaxationState(1, vecMinScore, ftsMinRank, topKVec, topKFts, false, false);
    }

    public RelaxationState next(RelaxationState current, int effectiveHits) {
        if (effectiveHits >= minEffectiveHits) {
            return current.withStop(true, false);
        }
        if (current.round() >= maxRelaxRounds) {
            return current.withStop(true, true);
        }
        double vec = Math.max(current.vecMinScore() - 0.04, vecFloor);
        double fts = Math.max(current.ftsMinRank() - 0.02, ftsFloor);
        return new RelaxationState(
            current.round() + 1,
            vec,
            fts,
            current.topKVec() + 5,
            current.topKFts() + 5,
            false,
            false
        );
    }

    public record RelaxationState(
        int round,
        double vecMinScore,
        double ftsMinRank,
        int topKVec,
        int topKFts,
        boolean stop,
        boolean lowConfidence
    ) {
        public RelaxationState withStop(boolean stop, boolean lowConfidence) {
            return new RelaxationState(round, vecMinScore, ftsMinRank, topKVec, topKFts, stop, lowConfidence);
        }
    }
}
