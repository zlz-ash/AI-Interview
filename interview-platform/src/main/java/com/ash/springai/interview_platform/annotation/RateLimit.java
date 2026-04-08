package com.ash.springai.interview_platform.annotation;

import java.lang.annotation.Target;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Repeatable;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RateLimit.Container.class)
public @interface RateLimit {
    
    enum Dimension {
        /**
         * 全局限流：对所有请求统一限流
         */
        GLOBAL,
        /**
         * IP限流：按客户端IP地址限流
         */
        IP,
        /**
         * 用户限流：按用户ID限流
         */
        USER
    }

    Dimension dimension() default Dimension.GLOBAL;

    double count();

    long interval() default 1;

    TimeUnit timeUnit() default TimeUnit.SECONDS;

    long timeout() default 0;

    String fallback() default "";

    enum TimeUnit {
        MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Container{
        RateLimit[] value();
    }
}
