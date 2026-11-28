package com.usac.jobseeker.ats.infrastructure.adapter.out.auth;

import com.usac.jobseeker.ats.application.support.ActorCapabilityPort;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import org.springframework.stereotype.Component;

/**
 * Pregunta a `auth` por las capacidades con su caso de uso público (ARCH-04). Una cuenta que no
 * puede actuar, como una en periodo de gracia, se trata como sin capacidades: no puede postular
 * (BR-DATA-04). Se registra a sí mismo como componente porque es el único lugar de `ats` que nombra
 * un tipo de `auth`; su nombre evita el de los adaptadores equivalentes de `cv` y `job`.
 */
@Component
public class AuthActorCapabilities implements ActorCapabilityPort {

  private final ResolveActorUseCase resolveActor;

  public AuthActorCapabilities(ResolveActorUseCase resolveActor) {
    this.resolveActor = resolveActor;
  }

  @Override
  public boolean isCandidate(AccountId accountId) {
    return has(accountId, Capability.CANDIDATE);
  }

  @Override
  public boolean isRecruiter(AccountId accountId) {
    return has(accountId, Capability.RECRUITER);
  }

  private boolean has(AccountId accountId, Capability capability) {
    return resolveActor.execute(new ResolveActorQuery(accountId))
            instanceof ActorResolution.Resolved(var actor)
        && actor.has(capability);
  }
}
