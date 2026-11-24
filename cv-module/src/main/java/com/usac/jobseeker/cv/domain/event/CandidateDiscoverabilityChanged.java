package com.usac.jobseeker.cv.domain.event;

import com.usac.jobseeker.common.domain.model.AccountId;

/**
 * El candidato activó o desactivó su descubribilidad (BR-DATA-03). `matching` actualiza su copia al
 * instante para sacarlo de los sugeridos o volver a incluirlo; no recalcula ningún score.
 *
 * <p>Solo lleva identificadores y la versión: nunca el valor nuevo ni el contenido del perfil
 * (BR-DATA-01, ADR-0016). Quien lo consume lee el dato por la superficie de lectura de `cv`.
 */
public final class CandidateDiscoverabilityChanged {

  public static final String EVENT_TYPE = "candidate.discoverability_changed";

  private CandidateDiscoverabilityChanged() {}

  /** El JSON de la carga; todo son UUID y un número, sin nada que escapar. */
  public static String payload(AccountId accountId, long version) {
    return "{\"accountId\":\"" + accountId + "\",\"version\":" + version + "}";
  }
}
