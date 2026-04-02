package com.ash.springai.interview_platform.Entity;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record QueryRequest(
    @NotEmpty(message = "至少选择一个知识库")
    List<Long> knowledgeBaseIds,  // 支持多个知识库
    
    @NotBlank(message = "问题不能为空")
    String question
) {
    /**
     * 兼容单知识库查询（向后兼容）
     */
    public QueryRequest(Long knowledgeBaseId, String question) {
        this(List.of(knowledgeBaseId), question);
    }
}
