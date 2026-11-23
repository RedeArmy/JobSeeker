package com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config`, sin estereotipos aquí (ADR-0015, ARCH-02). Ni
 * la métrica ni el log llevan el contenido del perfil ni la cuenta: el `accountId` ya lo aporta
 * `ActorFilter` en cada línea de la petición y un dato personal nunca va a un log (BR-DATA-01).
 */
public class GetMyProfessionalProfileService implements GetMyProfessionalProfileUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(GetMyProfessionalProfileService.class);
  private static final String USE_CASE = "get_my_professional_profile";

  private final ProfessionalProfileReadPort profiles;
  private final UseCaseMetricsPort metrics;

  public GetMyProfessionalProfileService(
      ProfessionalProfileReadPort profiles, UseCaseMetricsPort metrics) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ProfessionalProfileView> execute(GetMyProfessionalProfileQuery query) {
    Optional<ProfessionalProfileView> view =
        profiles.findByAccountId(query.accountId()).map(ProfessionalProfileView::of);
    String outcome = view.isPresent() ? "found" : "not_created";
    metrics.count(USE_CASE, outcome);
    LOG.info(
        "event=professional_profile_viewed outcome={} Consulta del propio perfil profesional",
        outcome);
    return view;
  }
}
