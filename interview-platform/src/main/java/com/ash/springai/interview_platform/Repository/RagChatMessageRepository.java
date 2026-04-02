package com.ash.springai.interview_platform.Repository;

import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

import com.ash.springai.interview_platform.Entity.RagChatMessageEntity;
import com.ash.springai.interview_platform.Entity.RagChatMessageEntity.MessageType;

@Repository
public interface RagChatMessageRepository extends JpaRepository<RagChatMessageEntity, Long> {
   
    List<RagChatMessageEntity> findBySessionIdOrderByMessageOrderAsc(Long sessionId);

    Optional<RagChatMessageEntity> findTopBySessionIdOrderByMessageOrderDesc(Long sessionId);

    @Query("SELECT COUNT(m) FROM RagChatMessageEntity m WHERE m.session.id = :sessionId")
    Integer countBySessionId(@Param("sessionId") Long sessionId);

    List<RagChatMessageEntity> findBySessionIdAndCompletedFalse(Long sessionId);

    void deleteBySessionId(Long sessionId);

    long countByType(MessageType type);
}
