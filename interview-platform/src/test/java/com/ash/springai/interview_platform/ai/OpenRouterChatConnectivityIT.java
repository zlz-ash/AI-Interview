package com.ash.springai.interview_platform.ai;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独立验证 OpenRouter Chat 是否可达、响应是否为对话内容而非站点 HTML（例如 404 页）。
 * <p>
 * 与 {@link com.ash.springai.interview_platform.service.AnswerEvaluationServiceApiIntegrationTests}
 * 相同点：base-url 含 {@code openrouter.ai} 时需 {@code completionsPath("/chat/completions")}，
 * 避免默认路径再拼一段 {@code /v1} 导致打到错误 URL、返回 HTML。
 * <p>
 * 运行：需设置环境变量 {@code OPENROUTER_API_KEY}，并具备外网（或可用代理）。
 */
@Tag("integration")
class OpenRouterChatConnectivityIT {

    @Test
    @DisplayName("OpenRouter Chat：显式 completionsPath 时应得到文本回复且非 HTML 页面")
    void shouldGetPlainTextCompletion_notHtml404() throws Exception {
        String apiKey = resolveOpenRouterApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "跳过：未配置 OPENROUTER_API_KEY");

        String baseUrl = firstNonBlank(
            System.getenv("OPENROUTER_BASE_URL"),
            resolveProperty("spring.ai.openai.chat.base-url")
        );
        Assumptions.assumeTrue(
            baseUrl != null && baseUrl.contains("openrouter.ai"),
            "跳过：当前 base-url 不是 OpenRouter（可设 OPENROUTER_BASE_URL 指向 openrouter）"
        );

        String model = firstNonBlank(
            System.getenv("OPENROUTER_MODEL"),
            resolveProperty("spring.ai.openai.chat.options.model")
        );
        Assumptions.assumeTrue(model != null && !model.isBlank(), "跳过：未解析到模型名");

        OpenAiApi openAiApi = OpenAiApi.builder()
            .baseUrl(baseUrl.trim())
            .apiKey(apiKey)
            .completionsPath("/chat/completions")
            .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.0)
                .maxCompletionTokens(32)
                .build())
            .observationRegistry(ObservationRegistry.NOOP)
            .build();

        ChatClient client = ChatClient.builder(chatModel).build();

        try {
            String content = client.prompt()
                .user("Reply with exactly this word and nothing else: PING")
                .call()
                .content();

            assertNotNull(content);
            String lower = content.toLowerCase();
            assertFalse(lower.contains("<!doctype"), "响应像 HTML 文档，可能 URL/路径错误或非 API 响应");
            assertFalse(content.contains("Not Found | OpenRouter"), "命中 OpenRouter 站点 404 页，检查 base-url 与 completionsPath");
            assertTrue(content.toUpperCase().contains("PING"), "期望模型回复含 PING，实际: " + content);
        } catch (Exception e) {
            String msg = flattenMessage(e);
            // 已打到 API 且 body 为 JSON 错误（非整页 HTML）时，说明 completionsPath/base-url 基本正确
            if (looksLikeOpenRouterJsonError(msg)) {
                assertFalse(msg.contains("<!DOCTYPE"), "API 错误响应不应为 HTML 整页（若出现则说明 URL/路径仍可能错误）");
                if (msg.contains("429") || msg.toLowerCase().contains("rate-limit")) {
                    throw new TestAbortedException("OpenRouter 上游 429/限流，连通性与路径检查已通过（JSON 非 HTML），跳过内容断言");
                }
                if (msg.contains("\"code\":401") || msg.contains("NonTransientAiException: 401")) {
                    throw new TestAbortedException("OpenRouter 鉴权失败（JSON），请检查 OPENROUTER_API_KEY");
                }
            }
            if (e instanceof NonTransientAiException) {
                throw (NonTransientAiException) e;
            }
            throw e;
        }
    }

    private static String flattenMessage(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        int depth = 0;
        while (t != null && depth++ < 8) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage());
            }
            t = t.getCause();
        }
        return sb.toString();
    }

    private static boolean looksLikeOpenRouterJsonError(String msg) {
        if (msg.contains("<!DOCTYPE")) {
            return false;
        }
        return msg.contains("\"code\":429")
            || msg.contains("\"code\":401")
            || msg.contains("\"code\":403")
            || msg.contains("Provider returned error")
            || msg.contains("rate-limited")
            || msg.contains("NonTransientAiException: 429")
            || msg.contains("NonTransientAiException: 401");
    }

    private static String resolveOpenRouterApiKey() throws Exception {
        String fromEnv = System.getenv("OPENROUTER_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return resolveProperty("spring.ai.openai.chat.api-key");
    }

    private static String resolveProperty(String propertyKey) throws Exception {
        Properties properties = new Properties();
        ClassPathResource mainProps = new ClassPathResource("application.properties");
        if (mainProps.exists()) {
            try (InputStream in = mainProps.getInputStream()) {
                properties.load(in);
            }
        }
        ClassPathResource localProps = new ClassPathResource("templates/application-local.properties");
        if (localProps.exists()) {
            try (InputStream in = localProps.getInputStream()) {
                properties.load(in);
            }
        }
        String raw = properties.getProperty(propertyKey);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        raw = raw.trim();
        if (raw.startsWith("${") && raw.endsWith("}")) {
            String ref = raw.substring(2, raw.length() - 1);
            String env = System.getenv(ref);
            if (env != null && !env.isBlank()) {
                return env.trim();
            }
            String fallback = properties.getProperty(ref);
            return fallback == null || fallback.isBlank() ? null : fallback.trim();
        }
        return raw;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
