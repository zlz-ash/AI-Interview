package com.ash.springai.interview_platform.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class StreamPartSseSerializerTest {

    @Test
    void serializesContentWithNewlines() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        StreamPartSseSerializer serializer = new StreamPartSseSerializer(objectMapper);

        String line = serializer.toJsonLine(StreamPart.content("a\nb"));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(line, Map.class);
        assertEquals("content", parsed.get("type"));
        assertEquals("a\nb", parsed.get("delta"));
    }
}
