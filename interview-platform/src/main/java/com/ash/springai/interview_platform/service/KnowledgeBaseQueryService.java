package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.document.Document;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;


import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;
import com.ash.springai.interview_platform.streaming.ChatResponseStreamMapper;
import com.ash.springai.interview_platform.streaming.StreamPart;
import com.ash.springai.interview_platform.Entity.HybridHitDTO;
import com.ash.springai.interview_platform.Entity.HybridMetaDTO;
import com.ash.springai.interview_platform.Entity.QueryRequest;
import com.ash.springai.interview_platform.Entity.QueryResponse;
import com.ash.springai.interview_platform.Repository.FtsSearchRepository;

@Slf4j
@Service
public class KnowledgeBaseQueryService {
    
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final Pattern SHORT_TOKEN_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_-]{2,20}$");
    private static final Pattern CJK_CHAR = Pattern.compile("[\\u4E00-\\u9FFF\\u3400-\\u4DBF\\uF900-\\uFAFF]");
    private static final int CJK_SHORT_TOKEN_MAX = 4;
    /** 流式首段探测窗口：过大会拖慢首包；需覆盖常见「无结果」句式前缀 */
    private static final int STREAM_PROBE_CHARS = 48;

    private final ChatClient chatClient;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final FtsSearchRepository ftsSearchRepository;
    private final QueryIntentClassifier queryIntentClassifier;
    private final RuleRerankService ruleRerankService;
    private final ThresholdRelaxationPolicy thresholdRelaxationPolicy;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;

    public KnowledgeBaseQueryService(
            ChatClient.Builder chatClientBuilder,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            FtsSearchRepository ftsSearchRepository,
            QueryIntentClassifier queryIntentClassifier,
            RuleRerankService ruleRerankService,
            ThresholdRelaxationPolicy thresholdRelaxationPolicy,
            @Value("classpath:prompts/knowledgebase-query-system.st") Resource systemPromptResource,
            @Value("classpath:prompts/knowledgebase-query-user.st") Resource userPromptResource,
            @Value("classpath:prompts/knowledgebase-query-rewrite.st") Resource rewritePromptResource,
            @Value("${app.ai.rag.rewrite.enabled:true}") boolean rewriteEnabled,
            @Value("${app.ai.rag.search.short-query-length:4}") int shortQueryLength,
            @Value("${app.ai.rag.search.topk-short:20}") int topkShort,
            @Value("${app.ai.rag.search.topk-medium:12}") int topkMedium,
            @Value("${app.ai.rag.search.topk-long:8}") int topkLong,
            @Value("${app.ai.rag.search.min-score-short:0.18}") double minScoreShort,
            @Value("${app.ai.rag.search.min-score-default:0.28}") double minScoreDefault) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.ftsSearchRepository = ftsSearchRepository;
        this.queryIntentClassifier = queryIntentClassifier;
        this.ruleRerankService = ruleRerankService;
        this.thresholdRelaxationPolicy = thresholdRelaxationPolicy;
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.rewritePromptTemplate = new PromptTemplate(rewritePromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.rewriteEnabled = rewriteEnabled;
        this.shortQueryLength = shortQueryLength;
        this.topkShort = topkShort;
        this.topkMedium = topkMedium;
        this.topkLong = topkLong;
        this.minScoreShort = minScoreShort;
        this.minScoreDefault = minScoreDefault;
    }

    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return NO_RESULT_RESPONSE;
        }

        // 1. 验证知识库是否存在并更新问题计数（合并数据库操作）
        countService.updateQuestionCounts(knowledgeBaseIds);

        // 2. Query rewrite + 动态参数检索（RAG）
        QueryContext queryContext = buildQueryContext(question);
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

        if (!hasEffectiveHit(question, relevantDocs)) {
            return NO_RESULT_RESPONSE;
        }

        // 3. 构建上下文（合并检索到的文档）
        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

        // 4. 构建提示词
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, question);

        try {
            // 5. 调用AI生成回答
            String answer = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);

            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            return answer;

        } catch (Exception e) {
            log.error("知识库问答失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：" + e.getMessage());
        }
    }

    private String buildSystemPrompt() {
        return systemPromptTemplate.render();
    }

    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        String question = normalizeQuestion(request.question());
        QueryContext queryContext = buildQueryContext(question);
        ThresholdRelaxationPolicy.RelaxationState relaxState = thresholdRelaxationPolicy.initial(
            queryContext.searchParams().minScore(),
            0.12,
            queryContext.searchParams().topK(),
            queryContext.searchParams().topK()
        );
        QueryIntentClassifier.QueryIntent intent = queryIntentClassifier.classify(question);
        List<HybridHitDTO> hybridHits = List.of();
        String mode = "QUERY_NO_EMBEDDING";
        boolean lowConfidence = false;

        while (!relaxState.stop()) {
            try {
                List<Document> vectorDocs = vectorService.similaritySearch(
                    queryContext.candidateQueries().getFirst(),
                    request.knowledgeBaseIds(),
                    relaxState.topKVec(),
                    relaxState.vecMinScore()
                );
                List<HybridHitDTO> vectorHits = toVectorHits(vectorDocs, request.knowledgeBaseIds());
                List<HybridHitDTO> ftsHits = ftsSearchRepository.search(
                    question,
                    request.knowledgeBaseIds(),
                    relaxState.topKFts(),
                    relaxState.ftsMinRank()
                );

                if (vectorHits.isEmpty()) {
                    mode = "QUERY_NO_EMBEDDING";
                    hybridHits = ruleRerankService.rerank(ftsHits, intent);
                } else {
                    mode = "QUERY_WITH_EMBEDDING";
                    hybridHits = mergeAndRerank(vectorHits, ftsHits, intent);
                }
            } catch (Exception e) {
                log.warn("向量检索不可用(round={})，回退 FTS: {}", relaxState.round(), e.getMessage());
                mode = "QUERY_NO_EMBEDDING";
                hybridHits = ruleRerankService.rerank(ftsSearchRepository.search(
                    question, request.knowledgeBaseIds(), relaxState.topKFts(), relaxState.ftsMinRank()
                ), intent);
            }

            relaxState = thresholdRelaxationPolicy.next(relaxState, hybridHits.size());
            log.debug("松弛策略 round={}, hits={}, stop={}, lowConf={}",
                relaxState.round(), hybridHits.size(), relaxState.stop(), relaxState.lowConfidence());
        }

        lowConfidence = relaxState.lowConfidence();

        String answer = answerQuestion(request.knowledgeBaseIds(), request.question());

        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);

        Long primaryKbId = request.knowledgeBaseIds().getFirst();

        HybridMetaDTO meta = new HybridMetaDTO(
            relaxState.round(),
            relaxState.vecMinScore(),
            relaxState.ftsMinRank(),
            intent.semanticWeight(),
            intent.keywordWeight(),
            lowConfidence
        );
        return QueryResponse.withSearch(answer, primaryKbId, kbNamesStr, mode, hybridHits, meta);
    }

    private List<HybridHitDTO> toVectorHits(List<Document> docs, List<Long> knowledgeBaseIds) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        Long fallbackKb = (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) ? 0L : knowledgeBaseIds.getFirst();
        return docs.stream()
            .map(doc -> {
                Object kbMeta = doc.getMetadata().get("kb_id");
                Long kbId = fallbackKb;
                if (kbMeta != null) {
                    try {
                        kbId = Long.parseLong(kbMeta.toString());
                    } catch (NumberFormatException ignored) {
                    }
                }
                String highlight = doc.getText() == null ? "" : truncate(doc.getText());
                Object chunkMeta = doc.getMetadata().get("id");
                String chunkId = chunkMeta == null ? String.valueOf(highlight.hashCode()) : chunkMeta.toString();
                return new HybridHitDTO(chunkId, kbId, "vector", 0.80, highlight, "向量相似度命中");
            })
            .toList();
    }

    private List<HybridHitDTO> mergeAndRerank(List<HybridHitDTO> vectorHits, List<HybridHitDTO> ftsHits, QueryIntentClassifier.QueryIntent intent) {
        List<HybridHitDTO> merged = new ArrayList<>();
        merged.addAll(vectorHits);
        merged.addAll(ftsHits);

        Set<String> seen = new HashSet<>();
        List<HybridHitDTO> deduped = merged.stream()
            .filter(hit -> seen.add(hit.chunkId() + ":" + hit.source()))
            .toList();

        return ruleRerankService.rerank(deduped, intent)
            .stream()
            .sorted(Comparator.comparingDouble(HybridHitDTO::score).reversed())
            .toList();
    }

    private String truncate(String text) {
        if (text.length() <= 240) {
            return text;
        }
        return text.substring(0, 240) + "...";
    }

    public Flux<StreamPart> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        log.info("收到知识库流式提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(StreamPart.content(NO_RESULT_RESPONSE));
        }

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 2. Query rewrite + 动态参数检索
            QueryContext queryContext = buildQueryContext(question);
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

            if (!hasEffectiveHit(question, relevantDocs)) {
                return Flux.just(StreamPart.content(NO_RESULT_RESPONSE));
            }

            // 3. 构建上下文
            String context = relevantDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用（chatResponse 拆分推理/正文）+ 探测窗口归一化（仅针对正文）
            Flux<StreamPart> responseFlux = ChatResponseStreamMapper.toStreamParts(
                chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .stream()
                    .chatResponse()
            );

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
            return normalizeStreamParts(responseFlux)
                .doOnComplete(() -> log.info("流式输出完成: kbIds={}", knowledgeBaseIds))
                .onErrorResume(e -> {
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                    return Flux.just(StreamPart.content("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。"));
                });

        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", e.getMessage(), e);
            return Flux.just(StreamPart.content("【错误】知识库查询失败：" + e.getMessage()));
        }
    }

        private QueryContext buildQueryContext(String originalQuestion) {
            String normalizedQuestion = normalizeQuestion(originalQuestion);
            String rewrittenQuestion = rewriteQuestion(normalizedQuestion);
            Set<String> candidates = new LinkedHashSet<>();
            candidates.add(rewrittenQuestion);
            candidates.add(normalizedQuestion);
    
            SearchParams searchParams = resolveSearchParams(normalizedQuestion);
            return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams);
        }

        private String normalizeQuestion(String question) {
            return question == null ? "" : question.trim();
        }

        private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
            for (String candidateQuery : queryContext.candidateQueries()) {
                if (candidateQuery.isBlank()) {
                    continue;
                }
                List<Document> docs = vectorService.similaritySearch(
                    candidateQuery,
                    knowledgeBaseIds,
                    queryContext.searchParams().topK(),
                    queryContext.searchParams().minScore()
                );
                log.info("检索候选 query='{}'，命中 {} 条", candidateQuery, docs.size());
                if (hasEffectiveHit(candidateQuery, docs)) {
                    return docs;
                }
            }
            return List.of();
        }

        private SearchParams resolveSearchParams(String question) {
            int compactLength = question.replaceAll("\\s+", "").length();
            if (compactLength <= shortQueryLength) {
                return new SearchParams(topkShort, minScoreShort);
            }
            if (compactLength <= 12) {
                return new SearchParams(topkMedium, minScoreDefault);
            }
            return new SearchParams(topkLong, minScoreDefault);
        }

        private String rewriteQuestion(String question) {
            if (!rewriteEnabled || question.isBlank()) {
                return question;
            }
            try {
                Map<String, Object> variables = new HashMap<>();
                variables.put("question", question);
                String rewritePrompt = rewritePromptTemplate.render(variables);
                String rewritten = chatClient.prompt()
                    .user(rewritePrompt)
                    .call()
                    .content();
                if (rewritten == null || rewritten.isBlank()) {
                    return question;
                }
                String normalized = rewritten.trim();
                log.info("Query rewrite: origin='{}', rewritten='{}'", question, normalized);
                return normalized;
            } catch (Exception e) {
                log.warn("Query rewrite 失败，使用原问题继续检索: {}", e.getMessage());
                return question;
            }
        }

        private boolean hasEffectiveHit(String question, List<Document> docs) {
            if (docs == null || docs.isEmpty()) {
                return false;
            }
    
            String normalized = normalizeQuestion(question);
            if (!isShortTokenQuery(normalized)) {
                return true;
            }
    
            String loweredToken = normalized.toLowerCase();
            for (Document doc : docs) {
                String text = doc.getText();
                if (text != null && text.toLowerCase().contains(loweredToken)) {
                    return true;
                }
            }
    
            log.info("短 query 命中确认失败，视为无有效结果: question='{}', docs={}", normalized, docs.size());
            return false;
        }

        private boolean isShortTokenQuery(String question) {
            if (question == null) {
                return false;
            }
            String compact = question.trim();
            if (!SHORT_TOKEN_PATTERN.matcher(compact).matches()) {
                return false;
            }
            if (CJK_CHAR.matcher(compact).find()) {
                return compact.length() <= CJK_SHORT_TOKEN_MAX;
            }
            return true;
        }

        private String normalizeAnswer(String answer) {
            if (answer == null || answer.isBlank()) {
                return NO_RESULT_RESPONSE;
            }
            String normalized = answer.trim();
            if (isNoResultLike(normalized)) {
                return NO_RESULT_RESPONSE;
            }
            return normalized;
        }

        private boolean isNoResultLike(String text) {
            return text.contains("没有找到相关信息")
                || text.contains("未检索到相关信息")
                || text.contains("信息不足")
                || text.contains("超出知识库范围")
                || text.contains("无法根据提供内容回答");
        }

        /**
         * 探测窗口仅针对 {@link StreamPart#TYPE_CONTENT}；探测完成前丢弃 reasoning，避免思考区闪现后再被截断。
         */
        private Flux<StreamPart> normalizeStreamParts(Flux<StreamPart> rawFlux) {
            return Flux.create(sink -> {
                StringBuilder probeBuffer = new StringBuilder();
                AtomicBoolean passthrough = new AtomicBoolean(false);
                AtomicBoolean completed = new AtomicBoolean(false);
                final Disposable[] disposableRef = new Disposable[1];

                disposableRef[0] = rawFlux.subscribe(
                    part -> {
                        if (completed.get() || sink.isCancelled()) {
                            return;
                        }
                        if (StreamPart.TYPE_REASONING.equals(part.type())) {
                            if (passthrough.get()) {
                                sink.next(part);
                            }
                            return;
                        }
                        if (passthrough.get()) {
                            sink.next(part);
                            return;
                        }

                        probeBuffer.append(part.delta());
                        String probeText = probeBuffer.toString();
                        if (isNoResultLike(probeText)) {
                            completed.set(true);
                            sink.next(StreamPart.content(NO_RESULT_RESPONSE));
                            sink.complete();
                            if (disposableRef[0] != null) {
                                disposableRef[0].dispose();
                            }
                            return;
                        }

                        if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                            passthrough.set(true);
                            sink.next(StreamPart.content(probeText));
                            probeBuffer.setLength(0);
                        }
                    },
                    sink::error,
                    () -> {
                        if (completed.get() || sink.isCancelled()) {
                            return;
                        }
                        if (!passthrough.get()) {
                            sink.next(StreamPart.content(normalizeAnswer(probeBuffer.toString())));
                        }
                        sink.complete();
                    }
                );

                sink.onCancel(() -> {
                    if (disposableRef[0] != null) {
                        disposableRef[0].dispose();
                    }
                });
            });
        }

        private record SearchParams(int topK, double minScore) {
        }

        private record QueryContext(String originalQuestion, List<String> candidateQueries, SearchParams searchParams) {
        }

}
