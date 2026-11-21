package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CandidateProfileRepository extends JpaRepository<CandidateProfileEntity, UUID> {
  @Query(
      "select c from CandidateProfileEntity c where c.discoverable = true order by cosine_distance(c.embedding, :q)")
  List<CandidateProfileEntity> nearest(@Param("q") float[] query, Pageable page);
}
