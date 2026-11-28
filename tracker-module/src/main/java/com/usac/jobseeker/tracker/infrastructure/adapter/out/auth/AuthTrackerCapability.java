package com.usac.jobseeker.tracker.infrastructure.adapter.out.auth;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import com.usac.jobseeker.tracker.application.support.CandidateCapabilityPort;
import org.springframework.stereotype.Component;

/**
 * Pregunta a `auth` por la capacidad con su caso de uso público (ARCH-04). Una cuenta que no puede
 * actuar se trata como sin capacidad. Su nombre evita el de los adaptadores equivalentes de `cv`,
 * `job` y `ats`, que también son componentes.
 */
@Component
public class AuthTrackerCapability implements CandidateCapabilityPort {

  private final ResolveActorUseCase resolveActor;

  public AuthTrackerCapability(ResolveActorUseCase resolveActor) {
    this.resolveActor = resolveActor;
  }

  @Override
  public boolean isCandidate(AccountId accountId) {
    return resolveActor.execute(new ResolveActorQuery(accountId))
            instanceof ActorResolution.Resolved(var actor)
        && actor.has(Capability.CANDIDATE);
  }
}
