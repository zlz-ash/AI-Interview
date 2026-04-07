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

import reactor.core.publisher.Flux;

import jakarta.validation.Valid;

import java.util.List;

import com.ash.springai.interview_platform.common.Result;
import com.ash.springai.interview_platform.Entity.RagChatDTO.*;
import com.ash.springai.interview_platform.service.RagChatSessionService;


@Slf4j
@RestController
@RequiredArgsConstructor
public class RagChatController {

    private final RagChatSessionService sessionService;

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

    @DeleteMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        sessionService.deleteSession(sessionId);
        return Result.success(null);
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

        // 2. 获取流式响应
        StringBuilder fullContent = new StringBuilder();

        Flux<ServerSentEvent<String>> contentEvents = sessionService.getStreamAnswer(sessionId, request.question())
            .doOnNext(fullContent::append)
            .map(chunk -> ServerSentEvent.<String>builder()
                .data(chunk.replace("\n", "\\n").replace("\r", "\\r"))
                .build());

        Flux<ServerSentEvent<String>> doneSignal = Flux.just(
            ServerSentEvent.<String>builder().data("[DONE]").build());

        return Flux.concat(contentEvents, doneSignal)
            .doOnComplete(() -> {
                sessionService.completeStreamMessage(messageId, fullContent.toString());
                log.info("RAG 聊天流式完成: sessionId={}, messageId={}", sessionId, messageId);
            })
            .doOnError(e -> {
                String content = !fullContent.isEmpty()
                    ? fullContent.toString()
                    : "【错误】回答生成失败：" + e.getMessage();
                sessionService.completeStreamMessage(messageId, content);
                log.error("RAG 聊天流式错误: sessionId={}", sessionId, e);
            });
    }
}
