package com.ash.springai.interview_platform.Repository;

import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ash.springai.interview_platform.Entity.InterviewSessionEntity;
import com.ash.springai.interview_platform.Entity.ResumeEntity;
import com.ash.springai.interview_platform.Entity.InterviewSessionEntity.SessionStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {
    
    Optional<InterviewSessionEntity> findBySessionId(String sessionId);

    @Query("SELECT s FROM InterviewSessionEntity s JOIN FETCH s.resume WHERE s.sessionId = :sessionId")
    Optional<InterviewSessionEntity> findBySessionIdWithResume(@Param("sessionId") String sessionId);

    List<InterviewSessionEntity> findByResumeOrderByCreatedAtDesc(ResumeEntity resume);

    List<InterviewSessionEntity> findByResumeIdOrderByCreatedAtDesc(Long resumeId);

    List<InterviewSessionEntity> findTop10ByResumeIdOrderByCreatedAtDesc(Long resumeId);

    Optional<InterviewSessionEntity> findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
        Long resumeId, 
        List<SessionStatus> statuses
    );

    Optional<InterviewSessionEntity> findByResumeIdAndStatusIn(
        Long resumeId,
        List<SessionStatus> statuses
    );
}
