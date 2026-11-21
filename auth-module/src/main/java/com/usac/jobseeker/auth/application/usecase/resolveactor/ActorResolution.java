package com.usac.jobseeker.auth.application.usecase.resolveactor;

import com.usac.jobseeker.common.domain.model.Actor;

/** Resultado de resolver el actor: o actúa, o se rechaza con un motivo que el filtro traduce. */
public sealed interface ActorResolution {

  /** La cuenta existe y está activa. */
  record Resolved(Actor actor) implements ActorResolution {}

  /** La cuenta no puede actuar. */
  record Rejected(Reason reason) implements ActorResolution {}

  enum Reason {
    /** No hay cuenta local: la sesión es anterior al aprovisionamiento. */
    NOT_PROVISIONED,
    /** En periodo de gracia de eliminación (BR-DATA-04). */
    PENDING_DELETION,
    /** Ya anonimizada (BR-DATA-05). */
    ANONYMIZED
  }
}
