package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import com.ash.springai.interview_platform.Entity.ResumeEntity;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeDeleteService {
    
    private final ResumePersistenceService persistenceService;
    private final InterviewPersistenceService interviewPersistenceService;
    private final FileStorageService storageService;

    public void deleteResume(Long id){
        log.info("收到删除简历请求: id={}", id);

        ResumeEntity resume = persistenceService.findById(id)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.RESUME_NOT_FOUND,"简历不存在"+id));

        try {
            storageService.deleteResume(resume.getStorageKey());
        } catch (Exception e) {
            log.warn("删除存储文件失败，继续删除数据库记录: {}", e.getMessage());
        }

        interviewPersistenceService.deleteSessionsByResumeId(id);

        persistenceService.deleteResume(id);

        log.info("简历删除完成: id={}", id);
    }
}
