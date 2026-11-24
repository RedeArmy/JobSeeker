package com.usac.jobseeker.cv.domain.event;

import com.usac.jobseeker.common.domain.model.AccountId;

/**
 * El candidato añadió o quitó una cuenta de su lista de exclusión de disponibilidad (BR-CAND-05).
 * Quien muestre el estado a esa cuenta (BR-VIS-06, BR-MATCH-09) debe dejar de usar lo que tenga en
 * caché.
 *
 * <p>Lleva solo identificadores de cuenta y la acción: nunca el estado de disponibilidad.
 */
public final class CandidateAvailabilityExclusionChanged {

  public static final String EVENT_TYPE = "candidate.availability_exclusion_changed";
  public static final String ADDED = "added";
  public static final String REMOVED = "removed";

  private CandidateAvailabilityExclusionChanged() {}

  /** El JSON de la carga; todo son UUID y un valor fijo, sin nada que escapar. */
  public static String payload(AccountId candidate, AccountId excluded, String action) {
    return "{\"accountId\":\""
        + candidate
        + "\",\"excludedAccountId\":\""
        + excluded
        + "\",\"action\":\""
        + action
        + "\"}";
  }
}
