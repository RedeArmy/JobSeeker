package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.Availability;

/**
 * Puerto de salida: el estado de disponibilidad de un candidato **tal como lo ve este reclutador**.
 * La lista de exclusión es de `cv` (BR-CAND-05, BR-VIS-06): para una cuenta excluida responde
 * `UNSET`, y `matching` no reimplementa esa regla ni puede olvidarla (BR-MATCH-09).
 */
public interface AvailabilityPort {

  Availability visibleTo(AccountId viewer, AccountId candidate);

  /**
   * La de varios candidatos con pocas consultas; todos aparecen en la respuesta. La implementación
   * por defecto pregunta uno a uno y el adaptador real la sustituye (TD-039).
   */
  default java.util.Map<AccountId, Availability> visibleToAll(
      AccountId viewer, java.util.Collection<AccountId> candidates) {
    java.util.Map<AccountId, Availability> all = new java.util.HashMap<>();
    for (AccountId candidate : candidates) {
      all.put(candidate, visibleTo(viewer, candidate));
    }
    return all;
  }
}
