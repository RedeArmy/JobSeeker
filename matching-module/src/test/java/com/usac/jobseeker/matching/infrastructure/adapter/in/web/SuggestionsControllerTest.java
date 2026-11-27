package com.usac.jobseeker.matching.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestCandidatesForJobQuery;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestedCandidate;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestionsResult;
import com.usac.jobseeker.matching.domain.model.AlgorithmVersion;
import com.usac.jobseeker.matching.domain.model.Availability;
import com.usac.jobseeker.matching.domain.model.CandidateCard;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.CompatibilityScore;
import com.usac.jobseeker.matching.domain.model.DataStatus;
import com.usac.jobseeker.matching.domain.model.Dimension;
import com.usac.jobseeker.matching.domain.model.DimensionScore;
import com.usac.jobseeker.matching.domain.model.ScoreBreakdown;
import com.usac.jobseeker.matching.infrastructure.config.MatchingProperties;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * El adaptador solo traduce: el reclutador sale del token y la respuesta no lleva el coseno crudo.
 */
class SuggestionsControllerTest {

  private static final AlgorithmVersion VERSION =
      new AlgorithmVersion("1", "modelo", "a".repeat(64), "b".repeat(64));

  private final UUID caller = UUID.randomUUID();
  private final UUID job = UUID.randomUUID();
  private final CandidateProfileId first = new CandidateProfileId(UUID.randomUUID());
  private final CandidateProfileId second = new CandidateProfileId(UUID.randomUUID());
  private final List<SuggestCandidatesForJobQuery> received = new ArrayList<>();
  private final Principal principal = caller::toString;

  private static SuggestedCandidate suggested(
      CandidateProfileId profile, double overall, Availability availability) {
    var dimension =
        new DimensionScore(
            Dimension.SKILLS,
            DataStatus.AVAILABLE,
            0.8,
            1.0,
            "Cubre 4 de 5 habilidades requeridas.");
    var breakdown = new ScoreBreakdown(List.of(dimension), true, 0.8, 0.6, 0.3, 0.9);
    var card = new CandidateCard("Backend", "GT", 4.5, "BACHELOR", List.of("Java"));
    return new SuggestedCandidate(
        profile, new CompatibilityScore(overall, breakdown, VERSION, false), availability, card);
  }

  private final SuggestionsController controller =
      new SuggestionsController(
          query -> {
            received.add(query);
            return new SuggestionsResult(
                SuggestionsResult.Status.OK,
                List.of(
                    suggested(first, 78.4, Availability.OPEN_TO_WORK),
                    suggested(second, 51.6, Availability.UNSET)),
                7,
                VERSION,
                false);
          },
          new MatchingProperties(
              null,
              null,
              new MatchingProperties.Suggestions(
                  200, Duration.ofHours(24), 50, 200, 0.5, Duration.ofDays(90)),
              new MatchingProperties.Retrieval(100),
              null));

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
  void theResponseRanksFromOneWithTheRoundedPercentageTheBreakdownAndTheCard() {
    var response = controller.suggestions(principal, job.toString(), 5);

    assertThat(response.status()).isEqualTo("OK");
    assertThat(response.remainingQuota()).isEqualTo(7);
    assertThat(response.algorithmVersion()).isEqualTo(VERSION.value());
    assertThat(response.calibrated()).isFalse();
    assertThat(response.candidates())
        .extracting(SuggestionsResponse.Candidate::rank)
        .containsExactly(1, 2);
    var top = response.candidates().get(0);
    assertThat(top.candidateProfileId()).isEqualTo(first.toString());
    assertThat(top.score().overall()).isEqualTo(78);
    assertThat(response.candidates().get(1).score().overall()).isEqualTo(52);
    assertThat(top.score().alpha()).isEqualTo(0.3);
    assertThat(top.score().completeness()).isEqualTo(0.9);
    assertThat(top.score().dimensions())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.dimension()).isEqualTo("SKILLS");
              assertThat(d.status()).isEqualTo("AVAILABLE");
              assertThat(d.explanation()).contains("4 de 5");
            });
    assertThat(top.availability()).isEqualTo("OPEN_TO_WORK");
    assertThat(top.profile().topSkills()).containsExactly("Java");
    assertThat(top.profile().yearsOfExperience()).isEqualTo(4.5);
  }

  // ADR-0017: el coseno bruto nunca llega a una respuesta; tampoco la cuenta ni datos de contacto.
  @Test
  void theResponseTypeHasNowhereToCarryTheRawCosineTheAccountOrContactData() {
    List<String> names = new ArrayList<>();
    for (Class<?> type : SuggestionsResponse.class.getDeclaredClasses()) {
      for (var component : type.getRecordComponents()) {
        names.add(component.getName().toLowerCase(java.util.Locale.ROOT));
      }
    }

    assertThat(names)
        .noneMatch(
            n ->
                n.contains("cosine")
                    || n.contains("similarity")
                    || n.contains("account")
                    || n.contains("email")
                    || n.contains("phone")
                    || n.contains("name"));
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

  @Test
  void anEmptyResultHasNoVersion() {
    var empty =
        new SuggestionsController(
            query ->
                new SuggestionsResult(SuggestionsResult.Status.PAUSED, List.of(), 3, null, false),
            new MatchingProperties(
                null,
                null,
                new MatchingProperties.Suggestions(
                    200, Duration.ofHours(24), 50, 200, 0.5, Duration.ofDays(90)),
                new MatchingProperties.Retrieval(100),
                null));

    var response = empty.suggestions(principal, job.toString(), 5);

    assertThat(response.status()).isEqualTo("PAUSED");
    assertThat(response.algorithmVersion()).isNull();
    assertThat(response.candidates()).isEmpty();
  }
}
