package com.usac.jobseeker.cv.infrastructure.adapter.out.auth;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorsQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorsUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.CandidateCapabilityPort;
import org.springframework.stereotype.Component;

/**
 * Pregunta a `auth` por las capacidades de la cuenta usando su caso de uso público: `cv` nunca lee
 * la tabla `accounts` (ARCH-04). Una cuenta que no puede actuar se trata como sin capacidad.
 *
 * <p>Se registra a sí mismo como componente porque es el único lugar donde `cv` puede nombrar un
 * tipo de `auth`: la configuración (`infrastructure.config`) solo ve el puerto propio de `cv`.
 */
@Component
public class AuthCandidateCapability implements CandidateCapabilityPort {

  private final ResolveActorUseCase resolveActor;
  private final ResolveActorsUseCase resolveActors;

  public AuthCandidateCapability(
      ResolveActorUseCase resolveActor, ResolveActorsUseCase resolveActors) {
    this.resolveActor = resolveActor;
    this.resolveActors = resolveActors;
  }

  // Todas las cuentas con una consulta (TD-039), con la misma regla que la individual.
  @Override
  public java.util.Set<AccountId> candidatesAmong(java.util.Collection<AccountId> accountIds) {
    java.util.Set<AccountId> candidates = new java.util.HashSet<>();
    resolveActors
        .execute(new ResolveActorsQuery(java.util.List.copyOf(accountIds)))
        .forEach(
            (id, resolution) -> {
              if (resolution instanceof ActorResolution.Resolved(var actor)
                  && actor.has(Capability.CANDIDATE)) {
                candidates.add(id);
              }
            });
    return candidates;
  }

  @Override
  public boolean isCandidate(AccountId accountId) {
    return resolveActor.execute(new ResolveActorQuery(accountId))
            instanceof ActorResolution.Resolved(var actor)
        && actor.has(Capability.CANDIDATE);
  }
}
