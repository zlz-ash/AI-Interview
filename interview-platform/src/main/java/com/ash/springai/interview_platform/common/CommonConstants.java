package com.ash.springai.interview_platform.common;

public final class CommonConstants {
    private CommonConstants() {}

    public static final class StatusCode {
        public static final int SUCCESS = 200;
        public static final int BAD_REQUEST = 400;
        public static final int UNAUTHORIZED = 401;
        public static final int FORBIDDEN = 403;
        public static final int NOT_FOUND = 404;
        public static final int SERVER_ERROR = 500;
        
        private StatusCode() {}
    }

    public static final class Pagination {
        public static final int DEFAULT_PAGE = 1;
        public static final int DEFAULT_SIZE = 20;
        public static final int MAX_SIZE = 100;
        
        private Pagination() {}
    }
}
