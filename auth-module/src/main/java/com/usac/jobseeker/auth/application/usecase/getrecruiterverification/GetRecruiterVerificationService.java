package com.usac.jobseeker.auth.application.usecase.getrecruiterverification;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config`, sin estereotipos aquí (ADR-0015, ARCH-02). Una
 * cuenta que no existe, no puede actuar o no es reclutadora no tiene verificación que consultar:
 * quien pregunta decide qué hacer con la ausencia, y la tratará como no autorizada.
 */
public class GetRecruiterVerificationService implements GetRecruiterVerificationUseCase {

  private static final String USE_CASE = "get_recruiter_verification";

  private final RecruiterVerificationReadPort accounts;
  private final UseCaseMetricsPort metrics;

  public GetRecruiterVerificationService(
      RecruiterVerificationReadPort accounts, UseCaseMetricsPort metrics) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<RecruiterVerification> execute(GetRecruiterVerificationQuery query) {
    Optional<RecruiterVerification> found = accounts.findOfActiveRecruiter(query.accountId());
    metrics.count(
        USE_CASE, found.map(v -> v.name().toLowerCase(java.util.Locale.ROOT)).orElse("absent"));
    return found;
  }
}
