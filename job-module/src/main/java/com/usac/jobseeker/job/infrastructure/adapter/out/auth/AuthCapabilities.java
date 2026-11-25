package com.usac.jobseeker.job.infrastructure.adapter.out.auth;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import com.usac.jobseeker.job.application.support.CapabilityPort;
import org.springframework.stereotype.Component;

/**
 * Pregunta a `auth` por las capacidades de la cuenta usando su caso de uso público: `job` nunca lee
 * la tabla `accounts` (ARCH-04). Una cuenta que no puede actuar se trata como sin capacidad.
 *
 * <p>Se registra a sí mismo como componente porque es el único lugar donde `job` puede nombrar un
 * tipo de `auth`: la configuración (`infrastructure.config`) solo ve el puerto propio de `job`.
 */
@Component
public class AuthCapabilities implements CapabilityPort {

  private final ResolveActorUseCase resolveActor;

  public AuthCapabilities(ResolveActorUseCase resolveActor) {
    this.resolveActor = resolveActor;
  }

  @Override
  public boolean isRecruiter(AccountId accountId) {
    return has(accountId, Capability.RECRUITER);
  }

  @Override
  public boolean isCandidate(AccountId accountId) {
    return has(accountId, Capability.CANDIDATE);
  }

  private boolean has(AccountId accountId, Capability capability) {
    return resolveActor.execute(new ResolveActorQuery(accountId))
            instanceof ActorResolution.Resolved(var actor)
        && actor.has(capability);
  }
}
