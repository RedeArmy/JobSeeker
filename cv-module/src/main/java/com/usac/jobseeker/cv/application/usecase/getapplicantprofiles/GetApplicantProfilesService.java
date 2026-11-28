package com.usac.jobseeker.cv.application.usecase.getapplicantprofiles;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.cv.domain.model.Anonymization;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Una consulta para todo el
 * pipeline. Una cuenta anonimizada sale siempre con el marcador y sin contacto, aunque quedara algo
 * en la fila: la regla se impone aquí y no se confía solo en el borrado de la anonimización.
 */
public class GetApplicantProfilesService implements GetApplicantProfilesUseCase {

  private static final String USE_CASE = "get_applicant_profiles";

  private final ApplicantProfileReadPort profiles;
  private final UseCaseMetricsPort metrics;

  public GetApplicantProfilesService(
      ApplicantProfileReadPort profiles, UseCaseMetricsPort metrics) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public List<ApplicantProfile> execute(GetApplicantProfilesQuery query) {
    if (query.accounts().isEmpty()) {
      return List.of();
    }
    List<ApplicantProfile> found =
        profiles.findApplicants(query.accounts()).stream()
            .map(GetApplicantProfilesService::masked)
            .toList();
    metrics.count(USE_CASE, "read");
    return found;
  }

  private static ApplicantProfile masked(ApplicantProfile profile) {
    if (!profile.anonymized()) {
      return profile;
    }
    return new ApplicantProfile(
        profile.accountId(), Anonymization.PLACEHOLDER_NAME, null, null, null, true);
  }
}
