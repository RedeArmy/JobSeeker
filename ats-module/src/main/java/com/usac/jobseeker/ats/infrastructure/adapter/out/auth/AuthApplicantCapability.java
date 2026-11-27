package com.usac.jobseeker.ats.infrastructure.adapter.out.auth;

import com.usac.jobseeker.ats.application.support.CandidateCapabilityPort;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import org.springframework.stereotype.Component;

/**
 * Pregunta a `auth` por la capacidad con su caso de uso público (ARCH-04). Una cuenta que no puede
 * actuar, como una en periodo de gracia, se trata como sin capacidad: no puede postular
 * (BR-DATA-04). Se registra a sí mismo como componente porque es el único lugar de `ats` que nombra
 * un tipo de `auth`.
 */
@Component
public class AuthApplicantCapability implements CandidateCapabilityPort {

  private final ResolveActorUseCase resolveActor;

  public AuthApplicantCapability(ResolveActorUseCase resolveActor) {
    this.resolveActor = resolveActor;
  }

  @Override
  public boolean isCandidate(AccountId accountId) {
    return resolveActor.execute(new ResolveActorQuery(accountId))
            instanceof ActorResolution.Resolved(var actor)
        && actor.has(Capability.CANDIDATE);
  }
}
