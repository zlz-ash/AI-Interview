package com.ash.springai.interview_platform.Repository;

import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

import com.ash.springai.interview_platform.Entity.ResumeEntity;

@Repository
public interface ResumeRepository extends JpaRepository<ResumeEntity,Long> {

    Optional<ResumeEntity> findByFileHash(String fileHash);

    boolean existsByFileHash(String fileHash);
}
