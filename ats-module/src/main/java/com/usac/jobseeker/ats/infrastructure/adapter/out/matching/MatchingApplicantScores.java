package com.usac.jobseeker.ats.infrastructure.adapter.out.matching;

import com.usac.jobseeker.ats.application.support.ApplicantScoresPort;
import com.usac.jobseeker.matching.application.usecase.getapplicantscores.ApplicantScore;
import com.usac.jobseeker.matching.application.usecase.getapplicantscores.GetApplicantScoresQuery;
import com.usac.jobseeker.matching.application.usecase.getapplicantscores.GetApplicantScoresUseCase;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Lee los puntajes del pipeline con el caso de uso público de `matching` (ARCH-04), en lote. */
@Component
public class MatchingApplicantScores implements ApplicantScoresPort {

  private final GetApplicantScoresUseCase scores;

  public MatchingApplicantScores(GetApplicantScoresUseCase scores) {
    this.scores = scores;
  }

  @Override
  public Map<UUID, Score> of(UUID jobPostingId, Collection<UUID> candidateProfileIds) {
    Map<UUID, Score> found = new HashMap<>();
    for (ApplicantScore s :
        scores.execute(
            new GetApplicantScoresQuery(jobPostingId, List.copyOf(candidateProfileIds)))) {
      found.put(s.candidateProfileId(), scoreOf(s));
    }
    return found;
  }

  private static Score scoreOf(ApplicantScore s) {
    ApplicantScore.Breakdown b = s.breakdown();
    Breakdown breakdown =
        b == null
            ? null
            : new Breakdown(
                b.completeness(),
                b.alpha(),
                b.structured(),
                b.semantic(),
                b.dimensions().stream()
                    .map(
                        d ->
                            new Dimension(
                                d.dimension(), d.status(), d.score(), d.weight(), d.explanation()))
                    .toList());
    return new Score(
        s.status(),
        s.overall(),
        s.calibrated(),
        s.algorithmVersion(),
        s.appliedOverall(),
        breakdown);
  }
}
