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


import com.ash.springai.interview_platform.streaming.ChatResponseStreamMapper;
import com.ash.springai.interview_platform.streaming.StreamPart;
import com.ash.springai.interview_platform.Entity.HybridHitDTO;
import com.ash.springai.interview_platform.Entity.RagEvaluationDTO.ContextChunkDTO;
import com.ash.springai.interview_platform.Entity.RagEvaluationDTO.EvaluateResponse;
import com.ash.springai.interview_platform.Repository.FtsSearchRepository;
import com.ash.springai.interview_platform.Repository.VectorStoreChunkContentRepository;
import com.ash.springai.interview_platform.enums.RetrievalMode;

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
    private final VectorStoreChunkContentRepository chunkContentRepository;

    public KnowledgeBaseQueryService(
            ChatClient.Builder chatClientBuilder,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseCountService countService,
            FtsSearchRepository ftsSearchRepository,
            VectorStoreChunkContentRepository chunkContentRepository,
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
        this.countService = countService;
        this.ftsSearchRepository = ftsSearchRepository;
        this.chunkContentRepository = chunkContentRepository;
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

    private String buildSystemPrompt() {
        return systemPromptTemplate.render();
    }

    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    private List<HybridHitDTO> computeHybridHits(List<Long> knowledgeBaseIds, String question) {
        String q = normalizeQuestion(question);
        QueryContext queryContext = buildQueryContext(q);
        ThresholdRelaxationPolicy.RelaxationState relaxState = thresholdRelaxationPolicy.initial(
            queryContext.searchParams().minScore(),
            0.12,
            queryContext.searchParams().topK(),
            queryContext.searchParams().topK()
        );
        QueryIntentClassifier.QueryIntent intent = queryIntentClassifier.classify(q);
        List<HybridHitDTO> hybridHits = List.of();

        while (!relaxState.stop()) {
            try {
                List<Document> vectorDocs = vectorService.similaritySearch(
                    queryContext.candidateQueries().getFirst(),
                    knowledgeBaseIds,
                    relaxState.topKVec(),
                    relaxState.vecMinScore()
                );
                List<HybridHitDTO> vectorHits = toVectorHits(vectorDocs, knowledgeBaseIds);
                List<HybridHitDTO> ftsHits = ftsSearchRepository.search(
                    q,
                    knowledgeBaseIds,
                    relaxState.topKFts(),
                    relaxState.ftsMinRank()
                );

                if (vectorHits.isEmpty()) {
                    hybridHits = ruleRerankService.rerank(ftsHits, intent);
                } else {
                    hybridHits = mergeAndRerank(vectorHits, ftsHits, intent);
                }
            } catch (Exception e) {
                log.warn("向量检索不可用(round={})，回退 FTS: {}", relaxState.round(), e.getMessage());
                hybridHits = ruleRerankService.rerank(ftsSearchRepository.search(
                    q, knowledgeBaseIds, relaxState.topKFts(), relaxState.ftsMinRank()
                ), intent);
            }

            relaxState = thresholdRelaxationPolicy.next(relaxState, hybridHits.size());
            log.debug("松弛策略 round={}, hits={}, stop={}, lowConf={}",
                relaxState.round(), hybridHits.size(), relaxState.stop(), relaxState.lowConfidence());
        }
        return hybridHits;
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
                // Spring AI PgVectorStore 把 vector_store.id(UUID) 放在 Document.getId()，
                // metadata 中通常不含 "id" 键；这里以 Document.getId() 为唯一来源，
                // 仅在极端缺失时退化为内容 hash 以兜底（同时避免链路崩溃）。
                String docId = doc.getId();
                String chunkId = (docId == null || docId.isBlank())
                    ? String.valueOf(highlight.hashCode())
                    : docId;
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

    /**
     * Ragas 评测专用：同步返回 {@link com.ash.springai.interview_platform.Entity.RagEvaluationDTO.EvaluateResponse}。
     * <p>
     * 与 {@link #answerQuestionStream} 的区别：
     *   - 非流式：阻塞调用 chatClient.call()，一次性拿到完整 answer
     *   - 返回 contexts：把检索到的 chunk 元信息 + 原文带回，供 ragas 计算
     *     context_precision / context_recall / faithfulness 等指标
     *   - 不更新 question_count：避免评测污染业务统计
     * <p>
     * 失败/无结果时返回 {@link #NO_RESULT_RESPONSE} + 空 contexts。
     */
    public com.ash.springai.interview_platform.Entity.RagEvaluationDTO.EvaluateResponse answerQuestionForEval(
            List<Long> knowledgeBaseIds, String question, RetrievalMode mode) {
        RetrievalMode effectiveMode = mode != null ? mode : RetrievalMode.HYBRID;
        log.info("[EVAL] 评测同步提问: kbIds={}, mode={}, question={}", knowledgeBaseIds, effectiveMode, question);

        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()
                || normalizeQuestion(question).isBlank()) {
            return new com.ash.springai.interview_platform.Entity.RagEvaluationDTO.EvaluateResponse(
                    NO_RESULT_RESPONSE, List.of());
        }

        try {
            // 1. 检索：与生产链路一致的策略；HYBRID 保留命中元信息（chunkId/score/source）
            QueryContext queryContext = buildQueryContext(question);
            List<Document> relevantDocs;
            List<HybridHitDTO> hybridHitsForOutput = List.of();
            if (effectiveMode == RetrievalMode.HYBRID) {
                hybridHitsForOutput = computeHybridHits(knowledgeBaseIds, question);
                LinkedHashSet<String> chunkIdOrder = new LinkedHashSet<>();
                for (HybridHitDTO hit : hybridHitsForOutput) {
                    chunkIdOrder.add(hit.chunkId());
                }
                relevantDocs = chunkContentRepository.loadDocumentsInOrder(new ArrayList<>(chunkIdOrder));
            } else {
                relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);
            }

            // 2. 空命中兜底
            if (!hasEffectiveHit(question, relevantDocs)) {
                return new com.ash.springai.interview_platform.Entity.RagEvaluationDTO.EvaluateResponse(
                        NO_RESULT_RESPONSE, List.of());
            }

            // 3. 构造评测用的 ContextChunkDTO 列表：以 relevantDocs 的顺序为准
            //    （即真正喂给 LLM 的顺序），并从 hybridHitsForOutput 回填 score/source
            Map<String, HybridHitDTO> hitById = new HashMap<>();
            for (HybridHitDTO h : hybridHitsForOutput) {
                hitById.putIfAbsent(h.chunkId(), h);
            }

            List<com.ash.springai.interview_platform.Entity.RagEvaluationDTO.ContextChunkDTO> contextChunks =
                new ArrayList<>();
            for (Document doc : relevantDocs) {
                String text = doc.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                String chunkId = doc.getId();
                HybridHitDTO hit = chunkId == null ? null : hitById.get(chunkId);
                Long kbId = null;
                Object kbMeta = doc.getMetadata() == null ? null : doc.getMetadata().get("kb_id");
                if (kbMeta != null) {
                    try { kbId = Long.parseLong(kbMeta.toString()); } catch (NumberFormatException ignored) { }
                }
                String source = hit != null ? hit.source() : (effectiveMode == RetrievalMode.VECTOR ? "vector" : "unknown");
                Double score = hit != null ? hit.score() : null;
                contextChunks.add(
                    new com.ash.springai.interview_platform.Entity.RagEvaluationDTO.ContextChunkDTO(
                        chunkId, kbId, source, score, text));
            }

            // 4. 同步调用 LLM 生成 answer（非流式）
            String promptContext = contextChunks.stream()
                .map(com.ash.springai.interview_platform.Entity.RagEvaluationDTO.ContextChunkDTO::text)
                .collect(Collectors.joining("\n\n---\n\n"));
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(promptContext, question);
            String rawAnswer = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();

            String finalAnswer = normalizeAnswer(rawAnswer);
            log.info("[EVAL] 评测完成: kbIds={}, ctxCount={}, answerLen={}",
                    knowledgeBaseIds, contextChunks.size(),
                    rawAnswer == null ? 0 : rawAnswer.length());
            return new com.ash.springai.interview_platform.Entity.RagEvaluationDTO.EvaluateResponse(
                    finalAnswer, contextChunks);
        } catch (Exception e) {
            log.error("[EVAL] 评测同步问答失败: {}", e.getMessage(), e);
            return new com.ash.springai.interview_platform.Entity.RagEvaluationDTO.EvaluateResponse(
                    "【错误】评测同步问答失败：" + e.getMessage(), List.of());
        }
    }

    public Flux<StreamPart> answerQuestionStream(List<Long> knowledgeBaseIds, String question, RetrievalMode mode) {
        RetrievalMode effectiveMode = mode != null ? mode : RetrievalMode.HYBRID;
        log.info("收到知识库流式提问: kbIds={}, mode={}, question={}", knowledgeBaseIds, effectiveMode, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(StreamPart.content(NO_RESULT_RESPONSE));
        }

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 2. 检索
            QueryContext queryContext = buildQueryContext(question);
            List<Document> relevantDocs;
            if (effectiveMode == RetrievalMode.HYBRID) {
                List<HybridHitDTO> hybridHits = computeHybridHits(knowledgeBaseIds, question);
                LinkedHashSet<String> chunkIdOrder = new LinkedHashSet<>();
                for (HybridHitDTO hit : hybridHits) {
                    chunkIdOrder.add(hit.chunkId());
                }
                relevantDocs = chunkContentRepository.loadDocumentsInOrder(new ArrayList<>(chunkIdOrder));
            } else {
                relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);
            }

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

    /**
     * 评测专用：同步版问答（非流式）。
     * <p>
     * 行为与 {@link #answerQuestionStream} 在「检索」阶段保持一致
     * （混合检索 / 候选查询 / 阈值松弛 / 短 query 命中判定等规则完全复用），
     * 但生成阶段走 {@code ChatClient.call()} 一次性拿到文本，
     * 并把「实际进入 LLM prompt 的文档片段」一并返回。
     * <p>
     * 返回的 contexts 顺序与 prompt 中 "\n\n---\n\n" 拼接顺序一致，
     * 便于 Ragas 的 context_precision 等位置敏感指标正确计算。
     *
     * @param knowledgeBaseIds 参与本次检索的知识库 ID 列表
     * @param question         用户问题
     * @param mode             检索模式（null 时默认 HYBRID）
     * @return answer + 命中的 context 列表；未命中时返回兜底文案 + 空 contexts
     */
    public EvaluateResponse answerQuestionForEvaluation(
            List<Long> knowledgeBaseIds, String question, RetrievalMode mode) {
        RetrievalMode effectiveMode = mode != null ? mode : RetrievalMode.HYBRID;
        log.info("[评测接口] 收到问答请求: kbIds={}, mode={}, question={}",
                knowledgeBaseIds, effectiveMode, question);

        // 入参为空 / 问题为空时，直接返回兜底，保持与流式链路一致
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()
                || normalizeQuestion(question).isBlank()) {
            return new EvaluateResponse(NO_RESULT_RESPONSE, List.of());
        }

        // 1. 更新问题计数（与线上流程一致，便于真实链路统计对齐）
        countService.updateQuestionCounts(knowledgeBaseIds);

        // 2. 检索（HYBRID 走混合检索；否则走候选 query 的纯向量检索）
        QueryContext queryContext = buildQueryContext(question);
        List<Document> relevantDocs;
        if (effectiveMode == RetrievalMode.HYBRID) {
            List<HybridHitDTO> hybridHits = computeHybridHits(knowledgeBaseIds, question);
            LinkedHashSet<String> chunkIdOrder = new LinkedHashSet<>();
            for (HybridHitDTO hit : hybridHits) {
                chunkIdOrder.add(hit.chunkId());
            }
            relevantDocs = chunkContentRepository.loadDocumentsInOrder(new ArrayList<>(chunkIdOrder));
        } else {
            relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);
        }

        // 3. 命中有效性判定（与流式链路保持一致）
        if (!hasEffectiveHit(question, relevantDocs)) {
            return new EvaluateResponse(NO_RESULT_RESPONSE, List.of());
        }

        // 4. 拼接上下文并构造 prompt
        String contextStr = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(contextStr, question);

        // 5. 同步调用 LLM 拿完整回答
        String rawAnswer;
        try {
            rawAnswer = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[评测接口] LLM 调用失败: {}", e.getMessage(), e);
            return new EvaluateResponse(
                    "【错误】知识库查询失败：" + e.getMessage(),
                    toContextChunks(relevantDocs));
        }
        String finalAnswer = normalizeAnswer(rawAnswer);

        // 6. 打包 contexts（含 chunkId / kbId / 原文），方便 ragas 下游分析
        List<ContextChunkDTO> contexts = toContextChunks(relevantDocs);
        log.info("[评测接口] 回答完成: kbIds={}, contextCount={}, answerLength={}",
                knowledgeBaseIds, contexts.size(), finalAnswer.length());

        return new EvaluateResponse(finalAnswer, contexts);
    }

    /**
     * 把 Document 列表转换为评测响应里的 ContextChunkDTO 列表。
     * <p>
     * score 字段当前置 null：因为在「加载命中文档内容」的阶段，
     * 混合检索的原始分数已经被 loadDocumentsInOrder 这一步丢弃；
     * 评测主要关心文本内容，不强依赖分数。
     */
    private List<ContextChunkDTO> toContextChunks(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        return docs.stream()
                .map(doc -> {
                    Long kbId = null;
                    Object kbMeta = doc.getMetadata().get("kb_id");
                    if (kbMeta != null) {
                        try {
                            kbId = Long.parseLong(kbMeta.toString());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    return new ContextChunkDTO(
                            doc.getId(),
                            kbId,
                            "hybrid",
                            null,
                            doc.getText() == null ? "" : doc.getText()
                    );
                })
                .toList();
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
         * 探测窗口仅针对 {@link StreamPart#TYPE_CONTENT}；
         * reasoning part 立即下发，不经过探测窗口缓存，保证推理过程实时流式展示。
         * 正文在探测窗口（{@value #STREAM_PROBE_CHARS} 字符）内检测是否为无结果兜底，
         * 若是则替换为 {@link #NO_RESULT_RESPONSE}；否则进入 passthrough 后逐段下发。
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
                            sink.next(part);
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
                            String finalAnswer = normalizeAnswer(probeBuffer.toString());
                            sink.next(StreamPart.content(finalAnswer));
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
