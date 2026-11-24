package com.usac.jobseeker.cv.domain.event;

import com.usac.jobseeker.common.domain.model.AccountId;

/**
 * El candidato declaró su estado de disponibilidad (BR-CAND-04). Cambia el orden de los sugeridos
 * (BR-MATCH-07) pero **nunca** recalcula un score: la señal de intención no es compatibilidad.
 *
 * <p>Solo lleva identificadores y la versión: nunca el valor nuevo ni el contenido del perfil
 * (BR-DATA-01, ADR-0016). Quien lo consume lee el dato por la superficie de lectura de `cv`.
 */
public final class CandidateAvailabilityChanged {

  public static final String EVENT_TYPE = "candidate.availability_changed";

  private CandidateAvailabilityChanged() {}

  /** El JSON de la carga; todo son UUID y un número, sin nada que escapar. */
  public static String payload(AccountId accountId, long version) {
    return "{\"accountId\":\"" + accountId + "\",\"version\":" + version + "}";
  }
}
