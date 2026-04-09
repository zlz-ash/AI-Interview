package com.ash.springai.interview_platform.streaming;

import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;

public final class DualChannelSse {

    private DualChannelSse() {
    }

    public static Flux<ServerSentEvent<String>> partsToSseEvents(Flux<StreamPart> parts, ObjectMapper objectMapper) {
        StreamPartSseSerializer serializer = new StreamPartSseSerializer(objectMapper);
        return parts.map(p -> ServerSentEvent.<String>builder().data(serializer.toJsonLine(p)).build());
    }
}
