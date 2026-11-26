package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import java.util.Optional;

/**
 * Puerto de salida: lo que `matching` sabe hoy de un candidato (capa anticorrupción hacia `cv`).
 * Vacío si la cuenta no tiene perfil legible: sin capacidad de candidato, sin perfil o anonimizado.
 * Quien lo llame no distingue por qué, y no debe: en los tres casos no hay nada que embeber.
 */
public interface CandidateSignalsReader {

  Optional<CandidateSignals> read(AccountId accountId);

  /**
   * Varias cuentas con pocas consultas, por cuenta pedida; las que no tienen perfil legible no
   * aparecen. La implementación por defecto lee una a una, correcta pero lenta: sirve a quien
   * simula el puerto y el adaptador real la sustituye (TD-039).
   */
  default java.util.Map<AccountId, CandidateSignals> readAll(
      java.util.Collection<AccountId> accountIds) {
    java.util.Map<AccountId, CandidateSignals> found = new java.util.HashMap<>();
    for (AccountId id : accountIds) {
      read(id).ifPresent(signals -> found.put(id, signals));
    }
    return found;
  }
}
