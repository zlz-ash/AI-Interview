package com.ash.springai.interview_platform.Repository;

import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ash.springai.interview_platform.Entity.ResumeAnalysisEntity;
import com.ash.springai.interview_platform.Entity.ResumeEntity;

import java.util.List;

@Repository
public interface ResumeAnalysisRepository extends JpaRepository<ResumeAnalysisEntity, Long> {
    
    List<ResumeAnalysisEntity> findByResumeOrderByAnalyzedAtDesc(ResumeEntity resume);

    ResumeAnalysisEntity findFirstByResumeIdOrderByAnalyzedAtDesc(Long resumeId);

    List<ResumeAnalysisEntity> findByResumeIdOrderByAnalyzedAtDesc(Long resumeId);
}
