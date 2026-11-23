package com.usac.jobseeker.cv.domain.event;

import com.usac.jobseeker.common.domain.model.AccountId;

/**
 * El perfil de una cuenta cambió (`ProfileUpdatedEvent`, domain-model.md §6): `matching` recalcula
 * lo que dependa de él (BR-MATCH-03, BR-ACCT-06).
 *
 * <p>Solo lleva identificadores, el tipo de cambio y la versión: nunca el contenido del perfil
 * (BR-DATA-01, ADR-0016). Quien lo consume lee el dato por la superficie de lectura de `cv`.
 */
public final class ProfileUpdated {

  public static final String AGGREGATE_TYPE = "professional_profile";
  public static final String EVENT_TYPE = "profile.updated";

  /** Cambió el nombre, el titular o la biografía. */
  public static final String CHANGE_IDENTITY = "identity";

  private ProfileUpdated() {}

  /** El JSON de la carga; todo son UUID, un número y un valor fijo, sin nada que escapar. */
  public static String payload(AccountId accountId, String change, long version) {
    return "{\"accountId\":\""
        + accountId
        + "\",\"change\":\""
        + change
        + "\",\"version\":"
        + version
        + "}";
  }
}
