package com.usac.jobseeker.matching.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EmbeddingVectorTest {

  private static final String HASH = "a".repeat(64);
  private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

  @Test
  void theVectorKeepsItsValuesAndDimensions() {
    var vector = EmbeddingVector.of(0.5f, -1f, 2.25f);

    assertThat(vector.dimensions()).isEqualTo(3);
    assertThat(vector.values()).containsExactly(0.5f, -1f, 2.25f);
  }

  @Test
  void changingTheArrayThatWasPassedInDoesNotChangeTheVector() {
    float[] source = {1f, 2f};
    var vector = EmbeddingVector.of(source);

    source[0] = 99f;

    assertThat(vector.values()).containsExactly(1f, 2f);
  }

  @Test
  void changingTheArrayThatWasReadDoesNotChangeTheVector() {
    var vector = EmbeddingVector.of(1f, 2f);

    vector.values()[0] = 99f;

    assertThat(vector.values()).containsExactly(1f, 2f);
  }

  @Test
  void anEmptyVectorOrOneWithNonFiniteValuesIsRejected() {
    assertThatThrownBy(EmbeddingVector::of).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EmbeddingVector.of((float[]) null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EmbeddingVector.of(1f, Float.NaN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EmbeddingVector.of(Float.POSITIVE_INFINITY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EmbeddingVector.of(Float.NEGATIVE_INFINITY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void twoVectorsWithTheSameValuesAreEqual() {
    assertThat(EmbeddingVector.of(1f, 2f))
        .isEqualTo(EmbeddingVector.of(1f, 2f))
        .hasSameHashCodeAs(EmbeddingVector.of(1f, 2f));
    assertThat(EmbeddingVector.of(1f, 2f))
        .isNotEqualTo(EmbeddingVector.of(1f, 3f))
        .isNotEqualTo("no es un vector");
  }

  @Test
  void theTextualFormNeverPrintsTheValues() {
    assertThat(EmbeddingVector.of(0.123f, 0.456f).toString())
        .isEqualTo("EmbeddingVector[2 dimensiones]")
        .doesNotContain("0.123");
  }

  @Test
  void anEmbeddingNeedsEveryPartAndAWellFormedHash() {
    var vector = EmbeddingVector.of(1f);

    assertThat(new EntityEmbedding(vector, "qwen3-embedding:0.6b", HASH, false, NOW).model())
        .isEqualTo("qwen3-embedding:0.6b");
    assertThatThrownBy(() -> new EntityEmbedding(null, "m", HASH, false, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EntityEmbedding(vector, null, HASH, false, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EntityEmbedding(vector, "m", null, false, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EntityEmbedding(vector, "m", HASH, false, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EntityEmbedding(vector, " ", HASH, false, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EntityEmbedding(vector, "m", "corto", false, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    String uppercaseHash = "A".repeat(64);

    assertThatThrownBy(() -> new EntityEmbedding(vector, "m", uppercaseHash, false, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theMetadataNeedsEveryPart() {
    assertThatThrownBy(() -> new EmbeddingMeta(null, HASH, false, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EmbeddingMeta("m", null, false, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EmbeddingMeta("m", HASH, false, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void theIdentifiersRejectNull() {
    assertThatThrownBy(() -> new CandidateProfileId(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new JobPostingId(null)).isInstanceOf(NullPointerException.class);
    var id = java.util.UUID.randomUUID();
    assertThat(new CandidateProfileId(id)).hasToString(id.toString());
    assertThat(new JobPostingId(id)).hasToString(id.toString());
  }
}
