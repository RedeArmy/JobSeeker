package com.usac.jobseeker.matching.application.usecase.getapplicantscores;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.ScoreStorePort;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.CompatibilityScore;
import com.usac.jobseeker.matching.domain.model.DimensionScore;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.ScoreBreakdown;
import com.usac.jobseeker.matching.domain.model.StoredScore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Solo lee lo guardado: el
 * puntaje de un postulante existe desde que postuló, o desde que llegó su vector (el recálculo crea
 * el que faltaba). Dos consultas para todo el pipeline, sin cálculo ni llamada al modelo.
 */
public class GetApplicantScoresService implements GetApplicantScoresUseCase {

  private static final String USE_CASE = "get_applicant_scores";

  private final ScoreStorePort store;
  private final UseCaseMetricsPort metrics;

  public GetApplicantScoresService(ScoreStorePort store, UseCaseMetricsPort metrics) {
    this.store = Objects.requireNonNull(store, "store");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public List<ApplicantScore> execute(GetApplicantScoresQuery query) {
    if (query.candidateProfileIds().isEmpty()) {
      return List.of();
    }
    JobPostingId job = new JobPostingId(query.jobPostingId());
    List<CandidateProfileId> profiles =
        query.candidateProfileIds().stream().map(CandidateProfileId::new).toList();
    Map<CandidateProfileId, StoredScore> current = store.findCurrentForJob(job, profiles);
    Map<CandidateProfileId, Double> applied = store.appliedOverallForJob(job, profiles);
    metrics.count(USE_CASE, "read");
    return profiles.stream()
        .map(profile -> scoreOf(profile, current.get(profile), applied.get(profile)))
        .toList();
  }

  private static ApplicantScore scoreOf(
      CandidateProfileId profile, StoredScore stored, Double appliedOverall) {
    if (stored == null) {
      return new ApplicantScore(
          profile.value(), ApplicantScore.PENDING, null, false, null, appliedOverall, null);
    }
    CompatibilityScore score = stored.score();
    return new ApplicantScore(
        profile.value(),
        ApplicantScore.OK,
        score.overall(),
        score.calibrated(),
        score.version().value(),
        appliedOverall,
        breakdownOf(score.breakdown()));
  }

  private static ApplicantScore.Breakdown breakdownOf(ScoreBreakdown breakdown) {
    return new ApplicantScore.Breakdown(
        breakdown.completeness(),
        breakdown.alpha(),
        breakdown.structured(),
        breakdown.semantic(),
        breakdown.dimensions().stream().map(GetApplicantScoresService::dimensionOf).toList());
  }

  private static ApplicantScore.Dimension dimensionOf(DimensionScore d) {
    return new ApplicantScore.Dimension(
        d.dimension().name(), d.status().name(), d.score(), d.weight(), d.explanation());
  }
}
