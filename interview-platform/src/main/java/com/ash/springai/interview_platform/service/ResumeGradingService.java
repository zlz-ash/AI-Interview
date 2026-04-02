package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.HashMap;


import com.ash.springai.interview_platform.Entity.ResumeAnalysisResponse;
import com.ash.springai.interview_platform.Entity.ResumeAnalysisResponse.ScoreDetail;
import com.ash.springai.interview_platform.Entity.ResumeAnalysisResponse.Suggestion;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;
import com.ash.springai.interview_platform.common.StructuredOutputInvoker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ResumeGradingService {

    private static final Logger log = LoggerFactory.getLogger(ResumeGradingService.class);
    
    private final ChatClient chatClient;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<ResumeAnalysisResponseDTO> outputConverter;

    private final StructuredOutputInvoker structuredOutputInvoker;

    private record ResumeAnalysisResponseDTO(
        int overallScore,
        ScoreDetailDTO scoreDetail,
        String summary,
        List<String> strengths,
        List<SuggestionDTO> suggestions
    ){}

    private record ScoreDetailDTO(
        int contentScore,
        int structureScore,
        int skillMatchScore,
        int expressionScore,
        int projectScore
    ) {}

    private record SuggestionDTO(
        String category,
        String priority,
        String issue,
        String recommendation
    ) {}

    public ResumeGradingService(
            ChatClient.Builder chatClientBuilder,
            StructuredOutputInvoker structuredOutputInvoker,
            @Value("classpath:prompts/resume-analysis-system.st") Resource systemPromptResource,
            @Value("classpath:prompts/resume-analysis-user.st") Resource userPromptResource) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.outputConverter = new BeanOutputConverter<>(ResumeAnalysisResponseDTO.class);
    }

    public ResumeAnalysisResponse analyzeResume(String resumeText){
        log.info("开始分析简历,文本长度: {}字符",resumeText.length());

        try{
            String systemPrompt = systemPromptTemplate.render();

            Map<String,Object> variables = new HashMap<>();
            variables.put("resumeText",resumeText);
            String userPrompt = userPromptTemplate.render(variables);

            String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();

            ResumeAnalysisResponseDTO dto;
            try{
                dto = structuredOutputInvoker.invoke(
                        chatClient,
                        systemPromptWithFormat,
                        userPrompt,
                        outputConverter,
                        ErrorCode.RESUME_ANALYSIS_FAILED,
                        "简历分析失败：",
                        "简历分析",
                        log
                );
                log.debug("AI响应解析成功:overallScore={}",dto.overallScore());
            }catch(Exception e){
                log.error("简历分析AI调用失败:{}",e.getMessage(),e);
                throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED,"简历分析失败:"+e.getMessage());
            }

            ResumeAnalysisResponse result = convertToResponse(dto,resumeText);
            log.info("简历分析完成,总分:{}",result.overallScore());

            return result;
        }catch(Exception e){
            log.error("简历分析失败:{}",e.getMessage(),e);
            return createErrorResponse(resumeText,e.getMessage());
        }
    }

    private ResumeAnalysisResponse convertToResponse(ResumeAnalysisResponseDTO dto, String originalText) {
        ScoreDetail scoreDetail = new ScoreDetail(
            dto.scoreDetail().contentScore(),
            dto.scoreDetail().structureScore(),
            dto.scoreDetail().skillMatchScore(),
            dto.scoreDetail().expressionScore(),
            dto.scoreDetail().projectScore()
        );

        List<Suggestion> suggestions = dto.suggestions().stream()
            .map(s -> new Suggestion(s.category(), s.priority(), s.issue(), s.recommendation()))
            .toList();

        return new ResumeAnalysisResponse(
            dto.overallScore(),
            scoreDetail,
            dto.summary(),
            dto.strengths(),
            suggestions,
            originalText
        );
    }

    private ResumeAnalysisResponse createErrorResponse(String originalText, String errorMessage) {
        // 用 errorMessage 等构造一个表示“失败”的 ResumeAnalysisResponse
        return new ResumeAnalysisResponse(
            0,
            new ScoreDetail(0, 0, 0, 0, 0),
            "分析过程中出现错误: " + errorMessage,
            List.of(),
            List.of(new Suggestion(
                "系统",
                "高",
                "AI分析服务暂时不可用",
                "请稍后重试，或检查AI服务是否正常运行"
            )),
            originalText
        );
    }
}
