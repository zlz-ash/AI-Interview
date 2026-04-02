package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.JacksonException;

import com.ash.springai.interview_platform.mapper.ResumeMapper;
import com.ash.springai.interview_platform.mapper.InterviewMapper;
import com.ash.springai.interview_platform.Entity.ResumeListItemDTO;
import com.ash.springai.interview_platform.Entity.ResumeEntity;
import com.ash.springai.interview_platform.Entity.ResumeAnalysisEntity;
import com.ash.springai.interview_platform.Entity.ResumeDetailDTO;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;
import com.ash.springai.interview_platform.Entity.ResumeAnalysisResponse;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeHistoryService {
    
    private final ResumePersistenceService resumePersistenceService;
    private final InterviewPersistenceService interviewPersistenceService;
    private final PdfExportService pdfExportService;
    private final ObjectMapper objectMapper;
    private final ResumeMapper resumeMapper;
    private final InterviewMapper interviewMapper;

    public List<ResumeListItemDTO> getAllResumes() {
        List<ResumeEntity> resumes = resumePersistenceService.findAllResumes();

        return resumes.stream().map(resume -> {
            // 获取最新分析结果的分数
            Integer latestScore = null;
            java.time.LocalDateTime lastAnalyzedAt = null;
            Optional<ResumeAnalysisEntity> analysisOpt = resumePersistenceService.getLatestAnalysis(resume.getId());
            if (analysisOpt.isPresent()) {
                ResumeAnalysisEntity analysis = analysisOpt.get();
                latestScore = analysis.getOverallScore();
                lastAnalyzedAt = analysis.getAnalyzedAt();
            }

            // 获取面试次数
            int interviewCount = interviewPersistenceService.findByResumeId(resume.getId()).size();

            // 使用 MapStruct 映射
            return new ResumeListItemDTO(
                resume.getId(),
                resume.getOriginalFilename(),
                resume.getFileSize(),
                resume.getUploadedAt(),
                resume.getAccessCount(),
                latestScore,
                lastAnalyzedAt,
                interviewCount
            );
        }).toList();
    }

    public ResumeDetailDTO getResumeDetail(Long id) {
        Optional<ResumeEntity> resumeOpt = resumePersistenceService.findById(id);
        if (resumeOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }

        ResumeEntity resume = resumeOpt.get();

        // 获取所有分析记录，使用 MapStruct 批量转换
        List<ResumeAnalysisEntity> analyses = resumePersistenceService.findAnalysesByResumeId(id);
        List<ResumeDetailDTO.AnalysisHistoryDTO> analysisHistory = resumeMapper.toAnalysisHistoryDTOList(
            analyses,
            this::extractStrengths,
            this::extractSuggestions
        );

        // 使用 InterviewMapper 转换面试历史
        List<Object> interviewHistory = interviewMapper.toInterviewHistoryList(
            interviewPersistenceService.findByResumeId(id)
        );

        return new ResumeDetailDTO(
            resume.getId(),
            resume.getOriginalFilename(),
            resume.getFileSize(),
            resume.getContentType(),
            resume.getStorageUrl(),
            resume.getUploadedAt(),
            resume.getAccessCount(),
            resume.getResumeText(),
            resume.getAnalyzeStatus(),
            resume.getAnalyzeError(),
            analysisHistory,
            interviewHistory
        );
    }

    private List<String> extractStrengths(ResumeAnalysisEntity entity) {
        try {
            if (entity.getStrengthsJson() != null) {
                return objectMapper.readValue(
                    entity.getStrengthsJson(),
                        new TypeReference<>() {
                        }
                );
            }
        } catch (JacksonException e) {
            log.error("解析 strengths JSON 失败", e);
        }
        return List.of();
    }

    private List<Object> extractSuggestions(ResumeAnalysisEntity entity) {
        try {
            if (entity.getSuggestionsJson() != null) {
                return objectMapper.readValue(
                    entity.getSuggestionsJson(),
                        new TypeReference<>() {
                        }
                );
            }
        } catch (JacksonException e) {
            log.error("解析 suggestions JSON 失败", e);
        }
        return List.of();
    }

    public ExportResult exportAnalysisPdf(Long resumeId) {
        Optional<ResumeEntity> resumeOpt = resumePersistenceService.findById(resumeId);
        if (resumeOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }

        ResumeEntity resume = resumeOpt.get();
        Optional<ResumeAnalysisResponse> analysisOpt = resumePersistenceService.getLatestAnalysisAsDTO(resumeId);
        if (analysisOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_NOT_FOUND);
        }

        try {
            byte[] pdfBytes = pdfExportService.exportResumeAnalysis(resume, analysisOpt.get());
            String filename = "简历分析报告_" + resume.getOriginalFilename() + ".pdf";

            return new ExportResult(pdfBytes, filename);
        } catch (Exception e) {
            log.error("导出PDF失败: resumeId={}", resumeId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出PDF失败: " + e.getMessage());
        }
    }

    public record ExportResult(byte[] pdfBytes, String filename) {}
}

