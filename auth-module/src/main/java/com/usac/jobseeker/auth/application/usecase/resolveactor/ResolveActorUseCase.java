package com.usac.jobseeker.auth.application.usecase.resolveactor;

/**
 * Reconstruye el `Actor` desde la base local en cada petición, nunca de claims del token
 * (ADR-0020). Una cuenta en eliminación o anonimizada no actúa.
 */
public interface ResolveActorUseCase {

  ActorResolution execute(ResolveActorQuery query);
}
