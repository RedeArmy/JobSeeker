package com.usac.jobseeker.auth.application.usecase.reviewrecruiter;

import com.usac.jobseeker.auth.application.usecase.changerecruiterverification.ChangeRecruiterVerificationCommand;
import com.usac.jobseeker.auth.application.usecase.changerecruiterverification.ChangeRecruiterVerificationUseCase;
import com.usac.jobseeker.common.application.audit.AuditEntry;
import com.usac.jobseeker.common.application.audit.AuditPort;
import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.security.OperatorPolicyPort;
import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config`, sin estereotipos aquí (ADR-0015, ARCH-02). Es
 * el único camino para pasar a un reclutador a `VERIFIED` (BR-ACCT-11), y por eso cada decisión
 * **pasa por tres controles**:
 *
 * <ol>
 *   <li>Quien llama es operador. Si no lo es, el canal **no existe** para él: `404`, no `403`, para
 *       no confirmar que hay un canal de verificación a quien no debe saberlo.
 *   <li>Nadie se verifica a sí mismo: una cuenta que sea a la vez operadora y reclutadora no puede
 *       darse a sí misma el tope alto de publicaciones.
 *   <li>El cambio queda en el registro de auditoría **en la misma transacción**: quien, a quién y a
 *       qué valor, sin datos personales (BR-DATA-01). Solo se audita un cambio real: repetir la
 *       misma decisión no deja rastro falso.
 * </ol>
 *
 * <p>La métrica y el log no llevan el identificador de ninguna cuenta (BR-DATA-01).
 */
public class ReviewRecruiterService implements ReviewRecruiterUseCase {

  private static final String USE_CASE = "review_recruiter";
  private static final String AUDIT_ACTION = "RECRUITER_VERIFICATION_CHANGED";
  private static final String AUDIT_TARGET = "ACCOUNT";

  private final OperatorPolicyPort operators;
  private final ChangeRecruiterVerificationUseCase change;
  private final AuditPort audit;
  private final UseCaseMetricsPort metrics;

  public ReviewRecruiterService(
      OperatorPolicyPort operators,
      ChangeRecruiterVerificationUseCase change,
      AuditPort audit,
      UseCaseMetricsPort metrics) {
    this.operators = Objects.requireNonNull(operators, "operators");
    this.change = Objects.requireNonNull(change, "change");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional
  public boolean execute(ReviewRecruiterCommand command) {
    if (!operators.isOperator(command.operator())) {
      metrics.count(USE_CASE, "not_operator");
      throw new NotFoundException();
    }
    if (command.operator().equals(command.target())) {
      metrics.count(USE_CASE, "self_review");
      throw new ValidationFailedException(
          List.of(new FieldViolation("accountId", "SELF_REVIEW_FORBIDDEN")));
    }
    boolean changed =
        change.execute(
            new ChangeRecruiterVerificationCommand(command.target(), command.decision()));
    if (changed) {
      audit.append(
          new AuditEntry(
              command.operator(),
              AUDIT_ACTION,
              AUDIT_TARGET,
              command.target().value(),
              Map.of("to", command.decision().name())));
    }
    metrics.count(USE_CASE, changed ? "changed" : "unchanged");
    return changed;
  }
}
