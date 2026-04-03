package com.ash.springai.interview_platform.service;

import com.ash.springai.interview_platform.Entity.InterviewQuestionDTO;
import com.ash.springai.interview_platform.Entity.InterviewQuestionDTO.QuestionType;
import com.ash.springai.interview_platform.Entity.InterviewReportDTO;
import com.ash.springai.interview_platform.common.StructuredOutputInvoker;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class AnswerEvaluationServiceApiIntegrationTests {

    @Test
    @DisplayName("smoke-6: 真实模型应返回完整评估报告")
    void shouldEvaluateSmokeQuestionSetWithRealApi() throws Exception {
        AnswerEvaluationService service = newServiceFromRealApi();
        List<InterviewQuestionDTO> questions = smokeQuestions();

        InterviewReportDTO report = service.evaluateInterview(
            "it-smoke-6",
            "5年Java后端经验，做过高并发接口优化、缓存治理、消息队列异步化与可观测性建设。",
            questions
        );

        assertBasicReport(report, questions.size());
    }

    @Test
    @DisplayName("full-16: 真实模型应在大题量下稳定返回")
    @EnabledIfEnvironmentVariable(named = "RUN_FULL_EVALUATION_IT", matches = "(?i)true")
    void shouldEvaluateFullQuestionSetWithRealApi() throws Exception {
        AnswerEvaluationService service = newServiceFromRealApi();
        List<InterviewQuestionDTO> questions = fullQuestions();

        InterviewReportDTO report = service.evaluateInterview(
            "it-full-16",
            "7年Java后端经验，负责过面试评估系统、向量检索与分布式缓存治理，擅长并发优化与数据库性能调优。",
            questions
        );

        assertBasicReport(report, questions.size());
    }

    private AnswerEvaluationService newServiceFromRealApi() throws Exception {
        String apiKey = firstNonBlank(
            System.getenv("OPENROUTER_API_KEY"),
            System.getenv("DEEPSEEK_API_KEY"),
            resolveProperty("spring.ai.openai.chat.api-key")
        );
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "DEEPSEEK_API_KEY not configured");

        String baseUrl = firstNonBlank(
            System.getenv("OPENROUTER_BASE_URL"),
            System.getenv("DEEPSEEK_BASE_URL"),
            resolveProperty("spring.ai.openai.chat.base-url")
        );
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.deepseek.com";
        }
        String model = firstNonBlank(
            System.getenv("OPENROUTER_MODEL"),
            System.getenv("DEEPSEEK_MODEL"),
            resolveProperty("spring.ai.openai.chat.options.model")
        );
        if (model == null || model.isBlank()) {
            model = "deepseek-chat";
        }

        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey);
        if (baseUrl.contains("openrouter.ai")) {
            // OpenRouter base-url commonly includes /api/v1 in this project config.
            // Override path to avoid duplicated /v1 segment from default OpenAI path.
            apiBuilder = apiBuilder.completionsPath("/chat/completions");
        }
        OpenAiApi openAiApi = apiBuilder.build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(model)
            .temperature(0.2)
            .maxCompletionTokens(6000)
            .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(options)
            .observationRegistry(ObservationRegistry.NOOP)
            .build();

        ChatClient.Builder chatClientBuilder = ChatClient.builder(chatModel);
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(2, true);

        Resource system = new ClassPathResource("prompts/interview-evaluation-system.st");
        Resource user = new ClassPathResource("prompts/interview-evaluation-user.st");
        Resource summarySystem = new ClassPathResource("prompts/interview-evaluation-summary-system.st");
        Resource summaryUser = new ClassPathResource("prompts/interview-evaluation-summary-user.st");

        return new AnswerEvaluationService(
            chatClientBuilder,
            invoker,
            system,
            user,
            summarySystem,
            summaryUser,
            4,
            8,
            8,
            6,
            15,
            30
        );
    }

    private void assertBasicReport(InterviewReportDTO report, int expectedTotalQuestions) {
        assertNotNull(report);
        assertEquals(expectedTotalQuestions, report.totalQuestions());
        assertNotNull(report.questionDetails());
        assertEquals(expectedTotalQuestions, report.questionDetails().size());
        assertNotNull(report.referenceAnswers());
        assertEquals(expectedTotalQuestions, report.referenceAnswers().size());
        assertNotNull(report.overallFeedback());
        assertFalse(report.overallFeedback().isBlank());
        assertNotNull(report.strengths());
        assertNotNull(report.improvements());
        assertTrue(report.overallScore() >= 0 && report.overallScore() <= 100);

        report.questionDetails().forEach(detail ->
            assertTrue(detail.score() >= 0 && detail.score() <= 100)
        );
    }

    private String resolveProperty(String propertyKey) throws Exception {
        Resource mainProps = new ClassPathResource("application.properties");
        Properties properties = new Properties();
        if (mainProps.exists()) {
            try (InputStream inputStream = mainProps.getInputStream()) {
                properties.load(inputStream);
            }
        }
        Resource localProps = new ClassPathResource("templates/application-local.properties");
        if (localProps.exists()) {
            try (InputStream inputStream = localProps.getInputStream()) {
                properties.load(inputStream);
            }
        }

        String raw = properties.getProperty(propertyKey);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        raw = raw.trim();
        if (raw.startsWith("${") && raw.endsWith("}")) {
            String referencedKey = raw.substring(2, raw.length() - 1);
            String envValue = System.getenv(referencedKey);
            if (envValue != null && !envValue.isBlank()) {
                return envValue;
            }
            String localValue = properties.getProperty(referencedKey);
            return (localValue == null || localValue.isBlank()) ? null : localValue.trim();
        }
        return raw;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private List<InterviewQuestionDTO> smokeQuestions() {
        return List.of(
            q(0, "请介绍你在最近一个后端项目中的核心职责，以及你最有价值的技术决策。", QuestionType.PROJECT, "项目经历",
                "我负责AI评估链路和数据模型设计。最关键的决策是把一次性评估改成分批评估加汇总，降低了结构化输出长度，解析失败率下降。"),
            q(1, "equals 和 hashCode 的契约是什么？如果违反会导致什么问题？", QuestionType.JAVA_BASIC, "Java基础",
                "相等对象必须有相同hashCode，不满足会导致HashMap和HashSet行为异常，比如查不到或去重失败。"),
            q(2, "ArrayList 和 LinkedList 在插入、随机访问场景下怎么选？", QuestionType.JAVA_COLLECTION, "集合",
                "随机访问优先ArrayList。中间频繁插入删除且已定位节点时LinkedList可能有优势，但大多数业务仍优先ArrayList。"),
            q(3, "线程池参数如何配置？你会重点关注哪些指标？", QuestionType.JAVA_CONCURRENT, "并发",
                "先区分CPU密集和IO密集，再结合核心数和队列容量配置。关注活跃线程、队列长度、拒绝次数和P95延迟。"),
            q(4, "说说你做过的一次 SQL 优化，具体怎么定位和验证效果？", QuestionType.MYSQL, "MySQL",
                "用explain发现回表和filesort，改联合索引并优化分页策略。上线后P95从800ms降到120ms。"),
            q(5, "在 Spring Boot 项目里你如何处理配置管理和环境隔离？", QuestionType.SPRING_BOOT, "SpringBoot",
                "用profile分环境，敏感信息走环境变量，配置用ConfigurationProperties集中管理。")
        );
    }

    private List<InterviewQuestionDTO> fullQuestions() {
        List<InterviewQuestionDTO> questions = new ArrayList<>(smokeQuestions());
        questions.add(q(6, "你如何理解 volatile 的可见性和有序性？", QuestionType.JAVA_CONCURRENT, "并发",
            "volatile保证可见性并限制重排序，但不保证复合操作原子性。"));
        questions.add(q(7, "synchronized 和 ReentrantLock 的取舍？", QuestionType.JAVA_CONCURRENT, "并发",
            "默认优先synchronized，需可中断、超时或多条件队列时用ReentrantLock。"));
        questions.add(q(8, "事务隔离级别及典型问题有哪些？", QuestionType.MYSQL, "MySQL",
            "读未提交有脏读，读已提交避免脏读，可重复读在MySQL下配合MVCC更常见。"));
        questions.add(q(9, "什么情况下会出现索引失效？", QuestionType.MYSQL, "MySQL",
            "函数处理索引列、前导模糊、隐式转换、范围后继续使用复合索引列都可能导致失效。"));
        questions.add(q(10, "缓存穿透、击穿、雪崩分别怎么处理？", QuestionType.REDIS, "Redis",
            "穿透用布隆过滤器或空值缓存，击穿用互斥重建，雪崩用过期打散和限流降级。"));
        questions.add(q(11, "你会怎么设计一个分布式锁？", QuestionType.REDIS, "Redis",
            "set nx px加唯一值，释放锁校验value并考虑续约、超时和幂等。"));
        questions.add(q(12, "Spring AOP 的实现原理和典型应用场景？", QuestionType.SPRING, "Spring",
            "AOP基于代理模式，适合日志、事务、鉴权、限流等横切关注点。"));
        questions.add(q(13, "事务传播行为里 REQUIRES_NEW 和 REQUIRED 的区别？", QuestionType.SPRING, "Spring",
            "REQUIRED复用当前事务，REQUIRES_NEW总是新开事务并挂起外层事务。"));
        questions.add(q(14, "你会如何做健康检查和可观测性？", QuestionType.SPRING_BOOT, "SpringBoot",
            "接入actuator、统一traceId、采集成功率和耗时并配置告警阈值。"));
        questions.add(q(15, "请讲一下你对灰度发布的实践理解。", QuestionType.SPRING_BOOT, "SpringBoot",
            "按比例逐步放量，观察错误率和延迟，异常立即回滚。"));
        return questions;
    }

    private InterviewQuestionDTO q(
        int index,
        String question,
        QuestionType type,
        String category,
        String userAnswer
    ) {
        return new InterviewQuestionDTO(index, question, type, category, userAnswer, null, null, false, null);
    }
}
