package com.usac.jobseeker.auth.application.usecase.changerecruiterverification;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config`, sin estereotipos aquí (ADR-0015, ARCH-02). El
 * cambio es condicional al valor que se leyó, así que dos operadores simultáneos no se pisan. Ni el
 * log ni la métrica llevan el identificador de la cuenta (BR-DATA-01): solo el valor nuevo.
 */
public class ChangeRecruiterVerificationService implements ChangeRecruiterVerificationUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(ChangeRecruiterVerificationService.class);
  private static final String USE_CASE = "change_recruiter_verification";

  private final RecruiterVerificationStorePort accounts;
  private final UseCaseMetricsPort metrics;

  public ChangeRecruiterVerificationService(
      RecruiterVerificationStorePort accounts, UseCaseMetricsPort metrics) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional
  public boolean execute(ChangeRecruiterVerificationCommand command) {
    RecruiterVerification current =
        accounts
            .findOfRecruiter(command.accountId())
            .orElseThrow(
                () -> {
                  metrics.count(USE_CASE, "not_found");
                  return new NotFoundException();
                });
    if (current == command.target()) {
      metrics.count(USE_CASE, "unchanged");
      return false;
    }
    if (!accounts.updateIfCurrent(command.accountId(), current, command.target())) {
      metrics.count(USE_CASE, "stale");
      throw new StaleStateException();
    }
    String outcome = command.target().name().toLowerCase(Locale.ROOT);
    metrics.count(USE_CASE, outcome);
    LOG.info(
        "event=recruiter_verification_changed outcome={} Verificación de reclutador cambiada",
        outcome);
    return true;
  }
}
