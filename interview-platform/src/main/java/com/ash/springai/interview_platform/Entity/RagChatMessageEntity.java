package com.ash.springai.interview_platform.Entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "rag_chat_messages", indexes = {
    @Index(name = "idx_rag_message_session", columnList = "session_id"),
    @Index(name = "idx_rag_message_order", columnList = "session_id, messageOrder")
})
@Getter
@Setter
@NoArgsConstructor
public class RagChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private RagChatSessionEntity session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageType type;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(nullable = false)
    private Integer messageOrder;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Boolean completed = true;

    public enum MessageType {
        USER,      // 用户消息
        ASSISTANT  // AI 回答
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

    public String getTypeString() {
        return type.name().toLowerCase();
    }
    
}
