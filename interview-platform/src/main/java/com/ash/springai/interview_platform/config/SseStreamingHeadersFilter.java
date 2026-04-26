package com.ash.springai.interview_platform.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SSE / 流式接口：关闭 Nginx 对响应体的缓冲（需配合 nginx {@code proxy_buffering off}），
 * 否则会出现长时间无输出、最后一次性刷完的现象。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SseStreamingHeadersFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !isSseStreamPath(uri);
    }

    private static boolean isSseStreamPath(String uri) {
        return uri.contains("/messages/stream");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        response.setHeader(HttpHeaders.CONNECTION, "keep-alive");
        filterChain.doFilter(request, response);
    }
}
