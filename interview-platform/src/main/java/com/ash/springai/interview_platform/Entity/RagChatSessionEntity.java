package com.ash.springai.interview_platform.Entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import com.ash.springai.interview_platform.enums.RetrievalMode;

@Entity
@Table(name = "rag_chat_sessions", indexes = {
    @Index(name = "idx_rag_session_updated", columnList = "updatedAt")
})
@Getter
@Setter
@NoArgsConstructor
public class RagChatSessionEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SessionStatus status = SessionStatus.ACTIVE;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "rag_session_knowledge_bases",
        joinColumns = @JoinColumn(name = "session_id"),
        inverseJoinColumns = @JoinColumn(name = "knowledge_base_id")
    )
    private Set<KnowledgeBaseEntity> knowledgeBases = new HashSet<>();

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("messageOrder ASC")
    private List<RagChatMessageEntity> messages = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer messageCount = 0;

    @Column(columnDefinition = "boolean default false")
    private Boolean isPinned = false;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private RetrievalMode retrievalMode = RetrievalMode.HYBRID;

    public enum SessionStatus {
        ACTIVE,    // 活跃会话
        ARCHIVED   // 已归档
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @PostLoad
    protected void onLoad() {
        // 确保 isPinned 字段始终有值（兼容旧数据）
        if (isPinned == null) {
            isPinned = false;
        }
        if (retrievalMode == null) {
            retrievalMode = RetrievalMode.HYBRID;
        }
    }

    public void addMessage(RagChatMessageEntity message) {
        messages.add(message);
        message.setSession(this);
        messageCount = messages.size();
        updatedAt = LocalDateTime.now();
    }

    public List<Long> getKnowledgeBaseIds() {
        return knowledgeBases.stream()
            .map(KnowledgeBaseEntity::getId)
            .toList();
    }

}
