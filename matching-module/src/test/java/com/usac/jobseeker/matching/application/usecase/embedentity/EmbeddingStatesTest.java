package com.usac.jobseeker.matching.application.usecase.embedentity;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.NearestProfile;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmbeddingStatesTest {

  @Test
  void aStateWithoutAnEmbeddingIsAnEmptyOptionalNeverNull() {
    assertThat(new ProfileEmbeddingState(true, null).embedding()).isEmpty();
    assertThat(new JobEmbeddingState(true, null).embedding()).isEmpty();
    assertThat(new ProfileEmbeddingState(false, Optional.empty()).embedding()).isEmpty();
  }

  @Test
  void aNearestProfileNeedsItsCandidate() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new NearestProfile(null, 0.5))
        .isInstanceOf(NullPointerException.class);
    assertThat(
            new NearestProfile(new CandidateProfileId(UUID.randomUUID()), 0.5).cosineSimilarity())
        .isEqualTo(0.5);
  }
}
