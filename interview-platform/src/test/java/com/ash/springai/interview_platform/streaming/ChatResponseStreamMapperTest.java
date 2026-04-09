package com.ash.springai.interview_platform.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import reactor.core.publisher.Flux;

class ChatResponseStreamMapperTest {

    @Test
    void cumulativeContentEmitsPrefixDeltas() {
        ChatResponse r1 = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("Hel").build())));
        ChatResponse r2 = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("Hello").build())));
        ChatResponse r3 = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("Hello world").build())));

        List<StreamPart> parts = ChatResponseStreamMapper.toStreamParts(Flux.just(r1, r2, r3))
            .collectList()
            .block(Duration.ofSeconds(5));

        assertEquals(3, parts.size());
        assertEquals(StreamPart.TYPE_CONTENT, parts.get(0).type());
        assertEquals("Hel", parts.get(0).delta());
        assertEquals("lo", parts.get(1).delta());
        assertEquals(" world", parts.get(2).delta());
    }

    @Test
    void reasoningInMetadataEmitsReasoningDeltaBeforeContent() {
        AssistantMessage m = AssistantMessage.builder()
            .content("x")
            .properties(Map.of("reasoning", "think"))
            .build();
        ChatResponse r = new ChatResponse(List.of(new Generation(m)));

        List<StreamPart> parts = ChatResponseStreamMapper.toStreamParts(Flux.just(r))
            .collectList()
            .block(Duration.ofSeconds(5));

        assertEquals(2, parts.size());
        assertEquals(StreamPart.TYPE_REASONING, parts.get(0).type());
        assertEquals("think", parts.get(0).delta());
        assertEquals(StreamPart.TYPE_CONTENT, parts.get(1).type());
        assertEquals("x", parts.get(1).delta());
    }

    @Test
    void nonPrefixContentReplacementEmitsFullNewTextAsDelta() {
        ChatResponse r1 = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("Hello").build())));
        ChatResponse r2 = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("Hi").build())));

        List<StreamPart> parts = ChatResponseStreamMapper.toStreamParts(Flux.just(r1, r2))
            .collectList()
            .block(Duration.ofSeconds(5));

        assertEquals(2, parts.size());
        assertEquals("Hello", parts.get(0).delta());
        assertEquals("Hi", parts.get(1).delta());
    }
}
