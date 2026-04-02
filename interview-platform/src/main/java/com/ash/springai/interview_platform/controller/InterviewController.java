package com.ash.springai.interview_platform.controller;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import com.ash.springai.interview_platform.service.InterviewSessionService;
import com.ash.springai.interview_platform.service.InterviewHistoryService;
import com.ash.springai.interview_platform.service.InterviewPersistenceService;
import com.ash.springai.interview_platform.annotation.RateLimit;
import com.ash.springai.interview_platform.Entity.CreateInterviewRequest;
import com.ash.springai.interview_platform.Entity.InterviewSessionDTO;
import com.ash.springai.interview_platform.common.Result;
import com.ash.springai.interview_platform.Entity.SubmitAnswerRequest;
import com.ash.springai.interview_platform.Entity.SubmitAnswerResponse;
import com.ash.springai.interview_platform.Entity.InterviewReportDTO;
import com.ash.springai.interview_platform.Entity.InterviewDetailDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class InterviewController {
    
    private final InterviewSessionService sessionService;
    private final InterviewHistoryService historyService;
    private final InterviewPersistenceService persistenceService;

    @PostMapping("/api/interview/sessions")
    @RateLimit(dimensions = {RateLimit.Dimension.GLOBAL, RateLimit.Dimension.IP}, count = 5)
    public Result<InterviewSessionDTO> createSession(@RequestBody CreateInterviewRequest request) {
        log.info("创建面试会话，题目数量: {}", request.questionCount());
        InterviewSessionDTO session = sessionService.createSession(request);
        return Result.success(session);
    }

    @GetMapping("/api/interview/sessions/{sessionId}")
    public Result<InterviewSessionDTO> getSession(@PathVariable String sessionId) {
        InterviewSessionDTO session = sessionService.getSession(sessionId);
        return Result.success(session);
    }

    @GetMapping("/api/interview/sessions/{sessionId}/question")
    public Result<Map<String, Object>> getCurrentQuestion(@PathVariable String sessionId) {
        return Result.success(sessionService.getCurrentQuestionResponse(sessionId));
    }

    @PostMapping("/api/interview/sessions/{sessionId}/answers")
    @RateLimit(dimensions = {RateLimit.Dimension.GLOBAL}, count = 10)
    public Result<SubmitAnswerResponse> submitAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        Integer questionIndex = (Integer) body.get("questionIndex");
        String answer = (String) body.get("answer");
        log.info("提交答案: 会话{}, 问题{}", sessionId, questionIndex);
        SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, questionIndex, answer);
        SubmitAnswerResponse response = sessionService.submitAnswer(request);
        return Result.success(response);
    }

    @GetMapping("/api/interview/sessions/{sessionId}/report")
    public Result<InterviewReportDTO> getReport(@PathVariable String sessionId) {
        log.info("生成面试报告: {}", sessionId);
        InterviewReportDTO report = sessionService.generateReport(sessionId);
        return Result.success(report);
    }

    @GetMapping("/api/interview/sessions/unfinished/{resumeId}")
    public Result<InterviewSessionDTO> findUnfinishedSession(@PathVariable Long resumeId) {
        // 未找到未完成会话属于正常情况：返回 200 + null，避免前端控制台刷 500
        return Result.success(sessionService.findUnfinishedSession(resumeId).orElse(null));
    }

    @PutMapping("/api/interview/sessions/{sessionId}/answers")
    public Result<Void> saveAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        Integer questionIndex = (Integer) body.get("questionIndex");
        String answer = (String) body.get("answer");
        log.info("暂存答案: 会话{}, 问题{}", sessionId, questionIndex);
        SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, questionIndex, answer);
        sessionService.saveAnswer(request);
        return Result.success(null);
    }

    @PostMapping("/api/interview/sessions/{sessionId}/complete")
    public Result<Void> completeInterview(@PathVariable String sessionId) {
        log.info("提前交卷: {}", sessionId);
        sessionService.completeInterview(sessionId);
        return Result.success(null);
    }

    @GetMapping("/api/interview/sessions/{sessionId}/details")
    public Result<InterviewDetailDTO> getInterviewDetail(@PathVariable String sessionId) {
        InterviewDetailDTO detail = historyService.getInterviewDetail(sessionId);
        return Result.success(detail);
    }

    @GetMapping("/api/interview/sessions/{sessionId}/export")
    public ResponseEntity<byte[]> exportInterviewPdf(@PathVariable String sessionId) {
        try {
            byte[] pdfBytes = historyService.exportInterviewPdf(sessionId);
            String filename = URLEncoder.encode("模拟面试报告_" + sessionId + ".pdf", 
                StandardCharsets.UTF_8);
            
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
        } catch (Exception e) {
            log.error("导出PDF失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/api/interview/sessions/{sessionId}")
    public Result<Void> deleteInterview(@PathVariable String sessionId) {
        log.info("删除面试会话: {}", sessionId);
        persistenceService.deleteSessionBySessionId(sessionId);
        return Result.success(null);
    }
}
