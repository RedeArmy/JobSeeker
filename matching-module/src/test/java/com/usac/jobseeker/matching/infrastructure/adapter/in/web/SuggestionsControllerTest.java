package com.usac.jobseeker.matching.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestCandidatesForJobQuery;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestionsResult;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.infrastructure.config.MatchingProperties;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El adaptador solo traduce: el reclutador sale del token y la respuesta no lleva puntaje. */
class SuggestionsControllerTest {

  private final UUID caller = UUID.randomUUID();
  private final UUID job = UUID.randomUUID();
  private final CandidateProfileId first = new CandidateProfileId(UUID.randomUUID());
  private final CandidateProfileId second = new CandidateProfileId(UUID.randomUUID());
  private final List<SuggestCandidatesForJobQuery> received = new ArrayList<>();
  private final Principal principal = caller::toString;

  private final SuggestionsController controller =
      new SuggestionsController(
          query -> {
            received.add(query);
            return new SuggestionsResult(SuggestionsResult.Status.OK, List.of(first, second), 7);
          },
          new MatchingProperties(
              null, null, new MatchingProperties.Suggestions(200, Duration.ofHours(24), 50)));

  @Test
  void theRecruiterComesFromTheTokenAndTheDefaultPageSizeIsTwenty() {
    controller.suggestions(principal, job.toString(), null);

    assertThat(received)
        .singleElement()
        .satisfies(
            query -> {
              assertThat(query.recruiter().value()).isEqualTo(caller);
              assertThat(query.job().value()).isEqualTo(job);
              assertThat(query.size()).isEqualTo(20);
            });
  }

  @Test
  void theResponseRanksFromOneAndCarriesOnlyOpaqueIdentifiers() {
    var response = controller.suggestions(principal, job.toString(), 5);

    assertThat(response.status()).isEqualTo("OK");
    assertThat(response.remainingQuota()).isEqualTo(7);
    assertThat(response.candidates())
        .containsExactly(
            new SuggestionsResponse.Candidate(1, first.toString()),
            new SuggestionsResponse.Candidate(2, second.toString()));
  }

  @Test
  void aSizeOutOfRangeIsAValidationError() {
    String jobId = job.toString();

    assertThatThrownBy(() -> controller.suggestions(principal, jobId, 0))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(() -> controller.suggestions(principal, jobId, 51))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void anIdentifierThatIsNotAUuidIsAJobThatDoesNotExist() {
    assertThatThrownBy(() -> controller.suggestions(principal, "no-es-un-uuid", 5))
        .isInstanceOf(NotFoundException.class);
  }
}
