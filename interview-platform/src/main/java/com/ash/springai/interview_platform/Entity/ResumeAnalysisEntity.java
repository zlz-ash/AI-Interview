package com.ash.springai.interview_platform.Entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "resume_analyses")
public class ResumeAnalysisEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    private ResumeEntity resume;

    private Integer overallScore;

    private Integer contentScore;      // 内容完整性 (0-25)
    private Integer structureScore;    // 结构清晰度 (0-20)
    private Integer skillMatchScore;   // 技能匹配度 (0-25)
    private Integer expressionScore;   // 表达专业性 (0-15)
    private Integer projectScore;      // 项目经验 (0-15)

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String strengthsJson;

    @Column(columnDefinition = "TEXT")
    private String suggestionsJson;

    @Column(nullable = false)
    private LocalDateTime analyzedAt;

    @PrePersist
    protected void onCreate() {
        analyzedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ResumeEntity getResume() {
        return resume;
    }
    
    public void setResume(ResumeEntity resume) {
        this.resume = resume;
    }

    public Integer getOverallScore() {
        return overallScore;
    }
    
    public void setOverallScore(Integer overallScore) {
        this.overallScore = overallScore;
    }

    public Integer getContentScore() {
        return contentScore;
    }
    
    public void setContentScore(Integer contentScore) {
        this.contentScore = contentScore;
    }

    public Integer getStructureScore() {
        return structureScore;
    }
    
    public void setStructureScore(Integer structureScore) {
        this.structureScore = structureScore;
    }

    public Integer getSkillMatchScore() {
        return skillMatchScore;
    }
    
    public void setSkillMatchScore(Integer skillMatchScore) {
        this.skillMatchScore = skillMatchScore;
    }

    public Integer getExpressionScore() {
        return expressionScore;
    }
    
    public void setExpressionScore(Integer expressionScore) {
        this.expressionScore = expressionScore;
    }

    public Integer getProjectScore() {
        return projectScore;
    }
    
    public void setProjectScore(Integer projectScore) {
        this.projectScore = projectScore;
    }

    public String getSummary() {
        return summary;
    }
    
    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getStrengthsJson() {
        return strengthsJson;
    }
    
    public void setStrengthsJson(String strengthsJson) {
        this.strengthsJson = strengthsJson;
    }

    public String getSuggestionsJson() {
        return suggestionsJson;
    }
    
    public void setSuggestionsJson(String suggestionsJson) {
        this.suggestionsJson = suggestionsJson;
    }

    public LocalDateTime getAnalyzedAt() {
        return analyzedAt;
    }
    
    public void setAnalyzedAt(LocalDateTime analyzedAt) {
        this.analyzedAt = analyzedAt;
    }
}
