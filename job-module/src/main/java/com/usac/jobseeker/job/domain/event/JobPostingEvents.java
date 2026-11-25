package com.usac.jobseeker.job.domain.event;

import com.usac.jobseeker.job.domain.model.JobPostingId;

/**
 * Los eventos de la vacante (ADR-0016). Solo llevan el identificador y la versión, nunca el
 * contenido ni al reclutador (BR-DATA-01): quien los consume lee el dato por la superficie de
 * lectura de `job`. Se publican en la misma transacción del cambio, de modo que no hay un cambio
 * sin evento ni un evento sin cambio.
 *
 * <p>`matching` mantiene con ellos su copia `published` y recalcula el vector cuando el contenido
 * cambia (BR-MATCH-03); publicar es la puerta del score (TDD de job §5, regla 6).
 */
public final class JobPostingEvents {

  /** Tipo de agregado en la bandeja: el orden de entrega es por vacante. */
  public static final String AGGREGATE_TYPE = "JOB_POSTING";

  public static final String UPDATED = "job.posting_updated";
  public static final String PUBLISHED = "job.posting_published";
  public static final String CLOSED = "job.posting_closed";
  public static final String REOPENED = "job.posting_reopened";
  public static final String ARCHIVED = "job.posting_archived";
  public static final String DELETED = "job.posting_deleted";
  public static final String RECRUITING_STAGE_CHANGED = "job.recruiting_stage_changed";

  private JobPostingEvents() {}

  /** El JSON de la carga; un UUID y un número, sin nada que escapar. */
  public static String payload(JobPostingId id, long version) {
    return "{\"jobPostingId\":\"" + id + "\",\"version\":" + version + "}";
  }
}
