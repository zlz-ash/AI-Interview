package com.ash.springai.interview_platform.Entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

import com.ash.springai.interview_platform.enums.RetrievalMode;

/**
 * RAG 评测专用 DTO 集合。
 * <p>
 * 该类仅用于对外暴露一个「同步」的问答接口给离线评测脚本（Ragas）使用：
 * 传入问题 + 指定的知识库（与 RetrievalMode）后，
 * 返回生成的最终 answer 以及一次检索命中的全部 contexts（片段原文）。
 * 这样 Python 端才能构造 ragas 需要的
 * {@code {user_input, retrieved_contexts, response, reference}} 数据集。
 */
public class RagEvaluationDTO {

    /**
     * 评测接口入参。
     * <ul>
     *   <li>{@code knowledgeBaseIds} - 参与本次检索的知识库 ID 列表</li>
     *   <li>{@code question}         - 用户原问题（评测集里的 user_input）</li>
     *   <li>{@code retrievalMode}    - 可选，默认 HYBRID；评测时通常保持与线上一致</li>
     * </ul>
     */
    public record EvaluateRequest(
        @NotEmpty(message = "至少选择一个知识库")
        List<Long> knowledgeBaseIds,

        @NotBlank(message = "问题不能为空")
        String question,

        RetrievalMode retrievalMode
    ) {}

    /**
     * 评测接口出参。
     * <ul>
     *   <li>{@code answer}   - 模型基于上下文生成的最终正文（无推理/无占位文案）</li>
     *   <li>{@code contexts} - 进入 LLM prompt 的文档片段（顺序与 prompt 拼接顺序一致）</li>
     * </ul>
     */
    public record EvaluateResponse(
        String answer,
        List<ContextChunkDTO> contexts
    ) {}

    /**
     * 单个命中片段的对外视图。
     * <p>
     * 保留 chunkId / knowledgeBaseId / source 方便评测阶段做定位与下钻分析；
     * text 是真正送入 LLM 的原文，供 ragas 计算
     * context_precision / context_recall / faithfulness 等指标使用。
     */
    public record ContextChunkDTO(
        String chunkId,
        Long knowledgeBaseId,
        String source,
        Double score,
        String text
    ) {}
}
