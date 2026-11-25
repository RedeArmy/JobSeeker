package com.usac.jobseeker.job.application.support;

import com.usac.jobseeker.common.domain.error.PublishingDeniedException;
import com.usac.jobseeker.common.domain.error.PublishingDeniedException.Reason;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import java.util.Objects;

/**
 * La puerta de publicar (REQ-R-10, amenaza T-N1: un reclutador falso que publica vacantes señuelo
 * para llegar a los candidatos). Se cruza al pasar a `PUBLISHED`, sea desde borrador o reabriendo
 * una cerrada; crear y editar borradores sigue libre (decisión D1 del TDD de `job`).
 *
 * <p>Se llama dentro de la transacción del caso de uso: el recuento toma antes un candado
 * transaccional por reclutador, de modo que dos publicaciones simultáneas de la misma cuenta se
 * ponen en fila y no pueden pasarse del tope juntas.
 */
public class PublishingGate {

  private final RecruiterStandingPort standings;
  private final JobPostingStorePort postings;
  private final PublishingPolicy policy;

  public PublishingGate(
      RecruiterStandingPort standings, JobPostingStorePort postings, PublishingPolicy policy) {
    this.standings = Objects.requireNonNull(standings, "standings");
    this.postings = Objects.requireNonNull(postings, "postings");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  /**
   * @throws PublishingDeniedException `SUSPENDED` si no tiene la confianza; `NOT_VERIFIED` si sin
   *     verificar ya está en su tope; `QUOTA_EXCEEDED` si verificada ya está en el suyo
   */
  public void requireAllowed(AccountId owner) {
    RecruiterVerification standing = standings.standingOf(owner);
    if (standing == RecruiterVerification.SUSPENDED) {
      throw new PublishingDeniedException(Reason.SUSPENDED);
    }
    if (postings.lockAndCountPublished(owner) >= policy.limitFor(standing)) {
      throw new PublishingDeniedException(
          standing == RecruiterVerification.UNVERIFIED
              ? Reason.NOT_VERIFIED
              : Reason.QUOTA_EXCEEDED);
    }
  }
}
