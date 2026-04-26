package com.ash.springai.interview_platform.streaming;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import reactor.core.publisher.Flux;

/**
 * Maps Spring AI streaming {@link ChatResponse} to internal {@link StreamPart}s.
 * <p>
 * Assumes upstream sends <strong>cumulative</strong> assistant text in each chunk (common for OpenAI-style
 * APIs). When a new snapshot is not a prefix extension of the previous one, the entire new snapshot is
 * emitted as the delta for that channel (handles occasional model resets).
 */
public final class ChatResponseStreamMapper {

    private static final List<String> REASONING_KEYS =
            List.of("reasoningContent", "reasoning", "reasoning_content", "thinking");

    private ChatResponseStreamMapper() {
    }

    /**
     * Sequential stateful mapping: each {@link ChatResponse} may emit 0–2 parts (reasoning first, then content).
     */
    public static Flux<StreamPart> toStreamParts(Flux<ChatResponse> chatResponses) {
        final String[] lastReasoning = {""};
        final String[] lastContent = {""};
        return chatResponses.concatMap(response -> {
            Generation gen = response.getResult();
            if (gen == null || gen.getOutput() == null) {
                return Flux.empty();
            }
            AssistantMessage msg = gen.getOutput();
            String newReasoningFull = extractReasoningFull(msg);
            String newContentFull = msg.getText() != null ? msg.getText() : "";
            String reasoningDelta = deltaFromCumulative(lastReasoning[0], newReasoningFull);
            String contentDelta = deltaFromCumulative(lastContent[0], newContentFull);
            lastReasoning[0] = newReasoningFull;
            lastContent[0] = newContentFull;

            List<StreamPart> parts = new ArrayList<>();
            if (!reasoningDelta.isEmpty()) {
                parts.add(StreamPart.reasoning(reasoningDelta));
            }
            if (!contentDelta.isEmpty()) {
                parts.add(StreamPart.content(contentDelta));
            }
            return Flux.fromIterable(parts);
        });
    }

    static String extractReasoningFull(AssistantMessage msg) {
        Map<String, Object> meta = msg.getMetadata();
        if (meta == null || meta.isEmpty()) {
            return "";
        }
        for (String key : REASONING_KEYS) {
            Object v = meta.get(key);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        return "";
    }

    static String deltaFromCumulative(String lastFull, String newFull) {
        String last = lastFull != null ? lastFull : "";
        String next = newFull != null ? newFull : "";
        if (next.startsWith(last)) {
            return next.substring(last.length());
        }
        return next;
    }
}
