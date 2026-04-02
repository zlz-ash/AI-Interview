package com.ash.springai.interview_platform.Repository;

import com.ash.springai.interview_platform.Entity.RagChatSessionEntity;
import com.ash.springai.interview_platform.Entity.RagChatSessionEntity.SessionStatus;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RagChatSessionRepository extends JpaRepository<RagChatSessionEntity,Long> {
    
    List<RagChatSessionEntity> findByStatusOrderByUpdatedAtDesc(SessionStatus status);

    List<RagChatSessionEntity> findAllByOrderByUpdatedAtDesc();

    @Query("SELECT s FROM RagChatSessionEntity s ORDER BY s.isPinned DESC, s.updatedAt DESC")
    List<RagChatSessionEntity> findAllOrderByPinnedAndUpdatedAtDesc();

    @Query("SELECT DISTINCT s FROM RagChatSessionEntity s JOIN s.knowledgeBases kb WHERE kb.id IN :kbIds ORDER BY s.updatedAt DESC")
    List<RagChatSessionEntity> findByKnowledgeBaseIds(@Param("kbIds") List<Long> knowledgeBaseIds);

    @Query("SELECT DISTINCT s FROM RagChatSessionEntity s LEFT JOIN FETCH s.knowledgeBases WHERE s.id = :id")
    Optional<RagChatSessionEntity> findByIdWithMessagesAndKnowledgeBases(@Param("id") Long id);

    @Query("SELECT s FROM RagChatSessionEntity s LEFT JOIN FETCH s.knowledgeBases WHERE s.id = :id")
    Optional<RagChatSessionEntity> findByIdWithKnowledgeBases(@Param("id") Long id);
}
