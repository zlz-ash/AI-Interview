package com.ash.springai.interview_platform.controller;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;

import com.ash.springai.interview_platform.streaming.DualChannelSse;
import com.ash.springai.interview_platform.streaming.StreamPart;

import jakarta.validation.Valid;

import java.util.List;

import com.ash.springai.interview_platform.common.Result;
import com.ash.springai.interview_platform.Entity.RagChatDTO.*;
import com.ash.springai.interview_platform.Entity.RagEvaluationDTO.EvaluateRequest;
import com.ash.springai.interview_platform.Entity.RagEvaluationDTO.EvaluateResponse;
import com.ash.springai.interview_platform.service.KnowledgeBaseQueryService;
import com.ash.springai.interview_platform.service.RagChatSessionService;


@Slf4j
@RestController
@RequiredArgsConstructor
public class RagChatController {

    private final RagChatSessionService sessionService;
    private final KnowledgeBaseQueryService knowledgeBaseQueryService;
    private final ObjectMapper objectMapper;

    @PostMapping("/api/rag-chat/sessions")
    public Result<SessionDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
        return Result.success(sessionService.createSession(request));
    }

    @GetMapping("/api/rag-chat/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        return Result.success(sessionService.listSessions());
    }

    @GetMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<SessionDetailDTO> getSessionDetail(@PathVariable Long sessionId) {
        return Result.success(sessionService.getSessionDetail(sessionId));
    }

    @PutMapping("/api/rag-chat/sessions/{sessionId}/title")
    public Result<Void> updateSessionTitle(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateTitleRequest request) {
        sessionService.updateSessionTitle(sessionId, request.title());
        return Result.success(null);
    }

    @PutMapping("/api/rag-chat/sessions/{sessionId}/pin")
    public Result<Void> togglePin(@PathVariable Long sessionId) {
        sessionService.togglePin(sessionId);
        return Result.success(null);
    }

    @PutMapping("/api/rag-chat/sessions/{sessionId}/knowledge-bases")
    public Result<Void> updateSessionKnowledgeBases(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateKnowledgeBasesRequest request) {
        sessionService.updateSessionKnowledgeBases(sessionId, request.knowledgeBaseIds());
        return Result.success(null);
    }

    @PutMapping("/api/rag-chat/sessions/{sessionId}/retrieval-mode")
    public Result<Void> updateRetrievalMode(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateRetrievalModeRequest request) {
        sessionService.updateRetrievalMode(sessionId, request.retrievalMode());
        return Result.success(null);
    }

    @DeleteMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        sessionService.deleteSession(sessionId);
        return Result.success(null);
    }

    /**
     * Ragas 评测专用端点：无状态、同步 Q→A。
     * <p>
     * 输入: {@link EvaluateRequest} 里指定 {@code knowledgeBaseIds / question / retrievalMode}（可选）。
     * 输出: {@link EvaluateResponse} 带上 {@code answer} 与 {@code contexts}（每条 context 含
     *       chunkId / kbId / source / score / text），供 Python 端构造 ragas 数据集。
     * <p>
     * 注意：本端点不落库、不更新 question_count；仅用于离线评测链路。
     */
    @PostMapping("/api/rag-chat/evaluate")
    public Result<EvaluateResponse> evaluate(@Valid @RequestBody EvaluateRequest request) {
        log.info("[EVAL] 收到评测请求: kbIds={}, mode={}, question={}",
            request.knowledgeBaseIds(), request.retrievalMode(), request.question());
        EvaluateResponse resp = knowledgeBaseQueryService.answerQuestionForEval(
            request.knowledgeBaseIds(), request.question(), request.retrievalMode());
        return Result.success(resp);
    }

    @PostMapping(value = "/api/rag-chat/sessions/{sessionId}/messages/stream",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendMessageStream(
            @PathVariable Long sessionId,
            @Valid @RequestBody SendMessageRequest request) {

        log.info("收到 RAG 聊天流式请求: sessionId={}, question={}, 线程: {} (虚拟线程: {})",
            sessionId, request.question(), Thread.currentThread(), Thread.currentThread().isVirtual());

        // 1. 准备消息（保存用户消息，创建 AI 消息占位）
        Long messageId = sessionService.prepareStreamMessage(sessionId, request.question());

        // 2. 获取流式响应（仅正文落库）
        StringBuilder contentOnly = new StringBuilder();

        Flux<ServerSentEvent<String>> contentEvents = DualChannelSse.partsToSseEvents(
            sessionService.getStreamAnswer(sessionId, request.question())
                .doOnNext(part -> {
                    if (StreamPart.TYPE_CONTENT.equals(part.type())) {
                        contentOnly.append(part.delta());
                    }
                }),
            objectMapper
        );

        Flux<ServerSentEvent<String>> doneSignal = Flux.just(
            ServerSentEvent.<String>builder().data("[DONE]").build());

        return Flux.concat(contentEvents, doneSignal)
            .doOnComplete(() -> {
                sessionService.completeStreamMessage(messageId, contentOnly.toString());
                log.info("RAG 聊天流式完成: sessionId={}, messageId={}", sessionId, messageId);
            })
            .doOnError(e -> {
                String content = contentOnly.length() > 0
                    ? contentOnly.toString()
                    : "【错误】回答生成失败：" + e.getMessage();
                sessionService.completeStreamMessage(messageId, content);
                log.error("RAG 聊天流式错误: sessionId={}", sessionId, e);
            });
    }
}
