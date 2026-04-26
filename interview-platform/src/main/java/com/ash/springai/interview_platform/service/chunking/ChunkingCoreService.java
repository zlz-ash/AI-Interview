package com.ash.springai.interview_platform.service.chunking;

import com.ash.springai.interview_platform.Entity.IngestChunkDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChunkingCoreService {

    public List<IngestChunkDTO> chunk(List<StructuredChunkCandidate> candidates, ChunkBudgetPolicy policy, TokenCounter tokenCounter) {
        List<IngestChunkDTO> out = new ArrayList<>();
        int chunkIndex = 0;
        for (StructuredChunkCandidate candidate : candidates) {
            String remaining = candidate.body() == null ? "" : candidate.body().trim();
            while (!remaining.isEmpty()) {
                String piece = tokenCounter.truncateToTokens(remaining, policy.targetMaxTokens()).trim();
                if (piece.isEmpty()) {
                    break;
                }

                int tokens = tokenCounter.count(piece);
                Map<String, Object> metadata = new HashMap<>();
                if (candidate.metadata() != null) {
                    metadata.putAll(candidate.metadata());
                }
                metadata.put("section_path", candidate.sectionPath());
                metadata.put("heading", candidate.heading());
                metadata.put("chunk_index", chunkIndex + 1);
                metadata.put("token_count", tokens);
                out.add(new IngestChunkDTO(++chunkIndex, piece, tokens, metadata));

                if (piece.length() >= remaining.length()) {
                    break;
                }

                String overlap = policy.overlapTokens() > 0
                    ? tokenCounter.truncateToTokens(piece, policy.overlapTokens()).trim()
                    : "";
                String tail = remaining.substring(piece.length()).trim();
                remaining = (overlap + "\n" + tail).trim();
            }
        }
        return out;
    }
}
