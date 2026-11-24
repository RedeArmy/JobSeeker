package com.usac.jobseeker.cv.application.usecase.getmycandidateprofile;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.CandidateCapabilityPort;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Ni la métrica ni el log
 * llevan el contenido del perfil, que incluye el contacto (BR-DATA-01).
 */
public class GetMyCandidateProfileService implements GetMyCandidateProfileUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(GetMyCandidateProfileService.class);
  private static final String USE_CASE = "get_my_candidate_profile";

  private final CandidateProfileReadPort profiles;
  private final CandidateCollectionsReadPort collections;
  private final CandidateCapabilityPort capabilities;
  private final UseCaseMetricsPort metrics;

  public GetMyCandidateProfileService(
      CandidateProfileReadPort profiles,
      CandidateCollectionsReadPort collections,
      CandidateCapabilityPort capabilities,
      UseCaseMetricsPort metrics) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.collections = Objects.requireNonNull(collections, "collections");
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<CandidateProfileView> execute(GetMyCandidateProfileQuery query) {
    // BR-ACCT-04: la capacidad de candidato se comporta como en una cuenta exclusiva de ese rol.
    if (!capabilities.isCandidate(query.accountId())) {
      metrics.count(USE_CASE, "forbidden");
      throw new ForbiddenCapabilityException();
    }
    Optional<CandidateProfileView> view =
        profiles
            .findByAccountId(query.accountId())
            .map(p -> CandidateProfileView.of(p, collections.findByProfileId(p.id())));
    String outcome = view.isPresent() ? "found" : "not_created";
    metrics.count(USE_CASE, outcome);
    LOG.info(
        "event=candidate_profile_viewed outcome={} Consulta del propio perfil de candidato",
        outcome);
    return view;
  }
}
