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
}
