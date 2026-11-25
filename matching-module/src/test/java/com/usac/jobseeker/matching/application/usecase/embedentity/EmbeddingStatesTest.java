package com.usac.jobseeker.matching.application.usecase.embedentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.NearestProfile;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmbeddingStatesTest {

  @Test
  void aStateWithoutAnEmbeddingCarriesAnEmptyOptional() {
    assertThat(new ProfileEmbeddingState(true, Optional.empty()).embedding()).isEmpty();
    assertThat(new JobEmbeddingState(false, false, Optional.empty()).embedding()).isEmpty();
  }

  @Test
  void aNullOptionalIsAProgrammingErrorAndIsRejected() {
    assertThatThrownBy(() -> new ProfileEmbeddingState(true, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new JobEmbeddingState(true, false, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aNearestProfileNeedsItsCandidate() {
    assertThatThrownBy(() -> new NearestProfile(null, 0.5))
        .isInstanceOf(NullPointerException.class);
    assertThat(
            new NearestProfile(new CandidateProfileId(UUID.randomUUID()), 0.5).cosineSimilarity())
        .isEqualTo(0.5);
  }
}
