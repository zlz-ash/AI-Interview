package com.ash.springai.interview_platform.streaming;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class StreamPartSseSerializer {

    private final ObjectMapper objectMapper;

    public StreamPartSseSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJsonLine(StreamPart part) {
        try {
            return objectMapper.writeValueAsString(
                Map.of("type", part.type(), "delta", part.delta()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("StreamPart JSON serialization failed", e);
        }
    }
}
