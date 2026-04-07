package com.ash.springai.interview_platform.service;

import com.ash.springai.interview_platform.Entity.HybridHitDTO;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class RuleRerankService {

    public List<HybridHitDTO> rerank(List<HybridHitDTO> hits, QueryIntentClassifier.QueryIntent intent) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
            .map(hit -> new HybridHitDTO(
                hit.chunkId(),
                hit.knowledgeBaseId(),
                hit.source(),
                score(hit, intent),
                hit.highlight(),
                buildReason(hit, intent)
            ))
            .sorted(Comparator.comparingDouble(HybridHitDTO::score).reversed())
            .toList();
    }

    private double score(HybridHitDTO hit, QueryIntentClassifier.QueryIntent intent) {
        double vecScore = "vector".equalsIgnoreCase(hit.source()) ? normalize(hit.score()) : 0.0;
        double ftsScore = "fts".equalsIgnoreCase(hit.source()) ? normalize(hit.score()) : 0.0;
        double hybrid = intent.semanticWeight() * vecScore + intent.keywordWeight() * ftsScore;
        double highlightBoost = computeHighlightBoost(hit.highlight());
        return 0.70 * hybrid + 0.10 * highlightBoost + 0.20 * normalize(hit.score());
    }

    private double computeHighlightBoost(String highlight) {
        if (highlight == null || highlight.isBlank()) return 0.0;
        int boldCount = countOccurrences(highlight, "<b>");
        if (boldCount >= 3) return 1.0;
        if (boldCount >= 1) return 0.6;
        return highlight.length() > 80 ? 0.3 : 0.1;
    }

    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private String buildReason(HybridHitDTO hit, QueryIntentClassifier.QueryIntent intent) {
        if ("vector".equalsIgnoreCase(hit.source())) {
            return "向量相似度命中(" + intent.label() + ")";
        }
        if ("fts".equalsIgnoreCase(hit.source())) {
            return "关键词FTS命中(" + intent.label() + ")";
        }
        return "混合检索命中(" + intent.label() + ")";
    }

    private double normalize(double score) {
        if (score < 0) {
            return 0;
        }
        if (score > 1) {
            return 1;
        }
        return score;
    }
}
