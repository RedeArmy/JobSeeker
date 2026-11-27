package com.usac.jobseeker.cv.application.usecase.checkapplicationreadiness;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileReadPort;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileReadPort;
import com.usac.jobseeker.cv.application.usecase.uploadcv.CvFileStorePort;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). La regla vive aquí, junto
 * al dato, y no en `ats`, que no sabe dónde guarda `cv` el nombre, el contacto o el CV.
 */
public class CheckApplicationReadinessService implements CheckApplicationReadinessUseCase {

  private static final String USE_CASE = "check_application_readiness";

  private final ProfessionalProfileReadPort professionals;
  private final CandidateProfileReadPort candidates;
  private final CvFileStorePort files;
  private final UseCaseMetricsPort metrics;

  public CheckApplicationReadinessService(
      ProfessionalProfileReadPort professionals,
      CandidateProfileReadPort candidates,
      CvFileStorePort files,
      UseCaseMetricsPort metrics) {
    this.professionals = Objects.requireNonNull(professionals, "professionals");
    this.candidates = Objects.requireNonNull(candidates, "candidates");
    this.files = Objects.requireNonNull(files, "files");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  // El nombre y el correo de contacto son obligatorios en sus perfiles, así que basta con que el
  // perfil exista. Un perfil anonimizado se lee como ausente (BR-DATA-05), y sus versiones del CV
  // también: una cuenta olvidada nunca está lista. Todo en una lectura para ver un mismo estado.
  @Override
  @Transactional(readOnly = true)
  public ApplicationReadiness execute(CheckApplicationReadinessQuery query) {
    Set<MissingRequirement> missing = EnumSet.noneOf(MissingRequirement.class);
    if (professionals.findByAccountId(query.accountId()).isEmpty()) {
      missing.add(MissingRequirement.FULL_NAME);
    }
    Optional<CandidateProfile> candidate = candidates.findByAccountId(query.accountId());
    if (candidate.isEmpty()) {
      missing.add(MissingRequirement.CONTACT);
    }
    Optional<UUID> cv =
        candidate.flatMap(profile -> files.current(profile.id())).map(file -> file.id());
    if (cv.isEmpty()) {
      missing.add(MissingRequirement.CV);
    }
    metrics.count(USE_CASE, missing.isEmpty() ? "ready" : "missing");
    return new ApplicationReadiness(missing, candidate.map(profile -> profile.id().value()), cv);
  }
}
