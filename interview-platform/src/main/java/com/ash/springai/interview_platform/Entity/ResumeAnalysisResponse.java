package com.ash.springai.interview_platform.Entity;

import java.util.List;

public record ResumeAnalysisResponse(
    int overallScore,
    ScoreDetail scoreDetail,
    String summary,
    List<String> strengths,
    List<Suggestion> suggestions,
    String originalText) {
    
        public record ScoreDetail(
            int contentScore,       // 内容完整性 (0-25)
            int structureScore,     // 结构清晰度 (0-20)
            int skillMatchScore,    // 技能匹配度 (0-25)
            int expressionScore,    // 表达专业性 (0-15)
            int projectScore        // 项目经验 (0-15)
        ) {}

        public record Suggestion(
            String category,        // 建议类别：内容、格式、技能、项目等
            String priority,        // 优先级：高、中、低
            String issue,           // 问题描述
            String recommendation   // 具体建议
        ) {}
}
