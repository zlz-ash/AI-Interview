package com.ash.springai.interview_platform.Entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

public record SubmitAnswerRequest(
    @NotBlank(message = "会话ID不能为空")
    String sessionId,
    
    @NotNull(message = "问题索引不能为空")
    @Min(value = 0, message = "问题索引无效")
    Integer questionIndex,
    
    @NotBlank(message = "答案不能为空")
    String answer
) {
    
}
