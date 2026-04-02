package com.ash.springai.interview_platform.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import com.ash.springai.interview_platform.Entity.InterviewAnswerEntity;
import com.ash.springai.interview_platform.Entity.InterviewSessionEntity;

@Repository
public interface InterviewAnswerRepository extends JpaRepository<InterviewAnswerEntity, Long> {
    List<InterviewAnswerEntity> findBySessionOrderByQuestionIndex(InterviewSessionEntity session);

    List<InterviewAnswerEntity> findBySessionIdOrderByQuestionIndex(Long sessionId);

    List<InterviewAnswerEntity> findBySession_SessionIdOrderByQuestionIndex(String sessionId);

    Optional<InterviewAnswerEntity> findBySession_SessionIdAndQuestionIndex(String sessionId, Integer questionIndex);
}
