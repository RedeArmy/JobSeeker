package com.usac.jobseeker.ats.domain.event;

import com.usac.jobseeker.ats.domain.model.Application;

/**
 * Los eventos de la postulación (ADR-0016, regla 8 del TDD de `ats`). Solo llevan identificadores,
 * la versión y el estado y la columna públicos, nunca datos del candidato ni el motivo interno de
 * un descarte (BR-DATA-01, BR-TRACK-03): quien los consuma, como las notificaciones (ADR-0022), lee
 * el resto por la superficie de lectura de `ats`. Se publican en la misma transacción del cambio.
 */
public final class ApplicationEvents {

  /** Tipo de agregado en la bandeja: el orden de entrega es por postulación. */
  public static final String AGGREGATE_TYPE = "APPLICATION";

  public static final String SUBMITTED = "ats.application_submitted";
  public static final String STATUS_CHANGED = "ats.application_status_changed";

  private ApplicationEvents() {}

  /** El JSON de la carga: UUID, un número y dos constantes de enum, sin nada que escapar. */
  public static String payload(Application application) {
    return "{\"applicationId\":\""
        + application.id()
        + "\",\"jobPostingId\":\""
        + application.jobPostingId()
        + "\",\"version\":"
        + application.version()
        + ",\"status\":\""
        + application.status()
        + "\",\"stage\":\""
        + application.stage()
        + "\"}";
  }
}
