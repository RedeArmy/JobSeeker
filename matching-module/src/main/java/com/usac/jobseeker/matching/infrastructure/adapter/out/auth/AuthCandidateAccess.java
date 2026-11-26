package com.usac.jobseeker.matching.infrastructure.adapter.out.auth;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import com.usac.jobseeker.matching.application.usecase.explainscore.CandidateAccessPort;
import org.springframework.stereotype.Component;

/**
 * Pregunta a `auth` si la cuenta puede actuar como candidata, con su caso de uso público (ARCH-04):
 * `matching` nunca lee la tabla `accounts`. Una cuenta que no puede actuar (en eliminación,
 * anonimizada, no aprovisionada) se trata como sin capacidad. Se registra a sí mismo como
 * componente porque es el único lugar donde `matching` nombra este tipo de `auth`.
 */
@Component
public class AuthCandidateAccess implements CandidateAccessPort {

  private final ResolveActorUseCase resolveActor;

  public AuthCandidateAccess(ResolveActorUseCase resolveActor) {
    this.resolveActor = resolveActor;
  }

  @Override
  public boolean isCandidate(AccountId account) {
    return resolveActor.execute(new ResolveActorQuery(account))
            instanceof ActorResolution.Resolved(var actor)
        && actor.has(Capability.CANDIDATE);
  }
}
