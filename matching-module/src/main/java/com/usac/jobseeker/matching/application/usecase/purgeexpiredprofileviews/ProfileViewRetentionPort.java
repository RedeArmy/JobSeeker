package com.usac.jobseeker.matching.application.usecase.purgeexpiredprofileviews;

import java.time.Instant;

/** Puerto de salida: borrar del registro de perfiles vistos lo anterior a un instante. */
public interface ProfileViewRetentionPort {

  /**
   * Borra como mucho {@code limit} filas vistas antes de {@code cutoff} y devuelve cuántas borró.
   * Por tandas, para que una purga grande no retenga candados ni crezca el WAL de golpe.
   */
  int purgeViewedBefore(Instant cutoff, int limit);
}
