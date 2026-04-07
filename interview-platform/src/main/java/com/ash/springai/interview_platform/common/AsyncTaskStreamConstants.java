package com.ash.springai.interview_platform.common;

public final class AsyncTaskStreamConstants {
    
    private AsyncTaskStreamConstants() {
        // 私有构造函数，防止实例化
    }

    public static final String FIELD_RETRY_COUNT = "retryCount";

    public static final String FIELD_CONTENT = "content";

    public static final String FIELD_STORAGE_KEY = "storageKey";

    public static final String FIELD_ORIGINAL_FILENAME = "originalFilename";

    public static final String FIELD_CONTENT_TYPE = "contentType";

    public static final String FIELD_INGEST_VERSION = "ingestVersion";

    public static final int MAX_RETRY_COUNT = 3;

    public static final int BATCH_SIZE = 10;

    public static final long POLL_INTERVAL_MS = 1000;

    public static final int STREAM_MAX_LEN = 1000;

    public static final String KB_VECTORIZE_STREAM_KEY = "knowledgebase:vectorize:stream";

    public static final String KB_VECTORIZE_GROUP_NAME = "vectorize-group";

    public static final String KB_VECTORIZE_CONSUMER_PREFIX = "vectorize-consumer-";

    public static final String FIELD_KB_ID = "kbId";

    public static final String RESUME_ANALYZE_STREAM_KEY = "resume:analyze:stream";

    public static final String RESUME_ANALYZE_GROUP_NAME = "analyze-group";

    public static final String RESUME_ANALYZE_CONSUMER_PREFIX = "analyze-consumer-";

    public static final String FIELD_RESUME_ID = "resumeId";

    public static final String INTERVIEW_EVALUATE_STREAM_KEY = "interview:evaluate:stream";

    public static final String INTERVIEW_EVALUATE_GROUP_NAME = "evaluate-group";

    public static final String INTERVIEW_EVALUATE_CONSUMER_PREFIX = "evaluate-consumer-";

    public static final String FIELD_SESSION_ID = "sessionId";
}
