package com.ash.springai.interview_platform.Entity;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.time.LocalDateTime;

import com.ash.springai.interview_platform.enums.RetrievalMode;

public class RagChatDTO {
    public record CreateSessionRequest(
        @NotEmpty(message = "至少选择一个知识库")
        List<Long> knowledgeBaseIds,

        String title,  // 可选，为空则自动生成

        RetrievalMode retrievalMode
    ) {}

    public record SendMessageRequest(
        @NotBlank(message = "问题不能为空")
        String question
    ) {}

    public record UpdateTitleRequest(
        @NotBlank(message = "标题不能为空")
        String title
    ) {}

    public record UpdateKnowledgeBasesRequest(
        @NotEmpty(message = "至少选择一个知识库")
        List<Long> knowledgeBaseIds
    ) {}

    public record SessionDTO(
        Long id,
        String title,
        List<Long> knowledgeBaseIds,
        LocalDateTime createdAt,
        RetrievalMode retrievalMode
    ) {}


    public record SessionListItemDTO(
        Long id,
        String title,
        Integer messageCount,
        List<String> knowledgeBaseNames,
        LocalDateTime updatedAt,
        Boolean isPinned
    ) {}

    public record SessionDetailDTO(
        Long id,
        String title,
        List<KnowledgeBaseListItemDTO> knowledgeBases,
        List<MessageDTO> messages,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        RetrievalMode retrievalMode
    ) {}

    public record UpdateRetrievalModeRequest(
        @NotNull(message = "retrievalMode 不能为空")
        RetrievalMode retrievalMode
    ) {}

    public record MessageDTO(
        Long id,
        String type,  // "user" | "assistant"
        String content,
        LocalDateTime createdAt
    ) {}
}
