package com.ash.springai.interview_platform.streaming;

public record StreamPart(String type, String delta) {
    public static final String TYPE_REASONING = "reasoning";
    public static final String TYPE_CONTENT = "content";

    public static StreamPart reasoning(String delta) {
        return new StreamPart(TYPE_REASONING, delta == null ? "" : delta);
    }

    public static StreamPart content(String delta) {
        return new StreamPart(TYPE_CONTENT, delta == null ? "" : delta);
    }
}
