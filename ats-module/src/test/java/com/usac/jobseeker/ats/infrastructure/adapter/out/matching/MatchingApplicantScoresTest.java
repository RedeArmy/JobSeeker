package com.usac.jobseeker.ats.infrastructure.adapter.out.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.ats.application.support.ApplicantScoresPort;
import com.usac.jobseeker.matching.application.usecase.getapplicantscores.ApplicantScore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Traduce los puntajes de `matching` a los tipos de `ats`, con desglose o pendientes. */
class MatchingApplicantScoresTest {

  private final UUID job = UUID.randomUUID();
  private final UUID scored = UUID.randomUUID();
  private final UUID pending = UUID.randomUUID();

  @Test
  void aScoreKeepsItsBreakdownAndAPendingOneHasNone() {
    var adapter =
        new MatchingApplicantScores(
            query -> {
              assertThat(query.jobPostingId()).isEqualTo(job);
              assertThat(query.candidateProfileIds()).containsExactly(scored, pending);
              return List.of(
                  new ApplicantScore(
                      scored,
                      ApplicantScore.OK,
                      72.4,
                      false,
                      "1|m|a|b",
                      60.0,
                      new ApplicantScore.Breakdown(
                          0.8,
                          0.3,
                          0.7,
                          0.75,
                          List.of(
                              new ApplicantScore.Dimension(
                                  "SKILLS", "AVAILABLE", 0.9, 0.35, "Java cubierto")))),
                  new ApplicantScore(
                      pending, ApplicantScore.PENDING, null, false, null, null, null));
            });

    Map<UUID, ApplicantScoresPort.Score> result = adapter.of(job, List.of(scored, pending));

    ApplicantScoresPort.Score ok = result.get(scored);
    assertThat(ok.isPending()).isFalse();
    assertThat(ok.overall()).isEqualTo(72.4);
    assertThat(ok.appliedOverall()).isEqualTo(60.0);
    assertThat(ok.algorithmVersion()).isEqualTo("1|m|a|b");
    assertThat(ok.breakdown().completeness()).isEqualTo(0.8);
    assertThat(ok.breakdown().dimensions())
        .containsExactly(
            new ApplicantScoresPort.Dimension("SKILLS", "AVAILABLE", 0.9, 0.35, "Java cubierto"));
    ApplicantScoresPort.Score none = result.get(pending);
    assertThat(none.isPending()).isTrue();
    assertThat(none.status()).isEqualTo(ApplicantScoresPort.Score.PENDING);
    assertThat(none.breakdown()).isNull();
  }
}
