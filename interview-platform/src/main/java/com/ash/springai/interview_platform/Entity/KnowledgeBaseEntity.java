package com.ash.springai.interview_platform.Entity;

import com.ash.springai.interview_platform.enums.DocumentType;
import com.ash.springai.interview_platform.enums.VectorStatus;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "knowledge_bases",indexes = {
    @Index(name = "idx_kb_hash",columnList = "fileHash",unique = true),
    @Index(name = "idx_kb_category",columnList = "category")
})
public class KnowledgeBaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false,unique = true,length = 64)
    private String fileHash;

    @Column(nullable = false)
    private String name;

    @Column(length = 100)
    private String category;

    @Column(nullable = false)
    private String originalFilename;

    private Long fileSize;

    private String contentType;

    @Column(length = 500)
    private String storageKey;

    @Column(length = 1000)
    private String storageUrl;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    private LocalDateTime lastAccessedAt;

    private Integer accessCount = 0;

    private Integer questionCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VectorStatus vectorStatus = VectorStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private DocumentType documentType;

    @Column(length = 32)
    private String ingestVersion;

    @Column(length = 32)
    private String ingestStatus;

    @Column(length = 500)
    private String vectorError;

    private Integer chunkCount = 0;

    @PrePersist
    protected void onCreate(){
        uploadedAt = LocalDateTime.now();
        lastAccessedAt = LocalDateTime.now();
        accessCount = 1;
    }

    public void incrementAccessCount() {
        this.accessCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }
    
    public void incrementQuestionCount() {
        this.questionCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }
}
