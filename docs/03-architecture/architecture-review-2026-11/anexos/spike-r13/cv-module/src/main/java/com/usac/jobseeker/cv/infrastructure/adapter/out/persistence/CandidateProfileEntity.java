package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "candidate_profiles")
public class CandidateProfileEntity {
  @Id private UUID id;

  @Column(nullable = false)
  private boolean discoverable;

  @JdbcTypeCode(SqlTypes.VECTOR)
  @Array(length = 1024)
  private float[] embedding;

  @Column(name = "embedding_model")
  private String embeddingModel;

  @Column(name = "embedding_dim")
  private Integer embeddingDim;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "embedding_content_hash", length = 64)
  private String embeddingContentHash;

  protected CandidateProfileEntity() {}

  public CandidateProfileEntity(
      UUID id, boolean discoverable, float[] embedding, String model, String hash) {
    this.id = id;
    this.discoverable = discoverable;
    this.embedding = embedding;
    this.embeddingModel = model;
    this.embeddingDim = embedding == null ? null : embedding.length;
    this.embeddingContentHash = hash;
  }

  public UUID getId() {
    return id;
  }

  public float[] getEmbedding() {
    return embedding;
  }
}
