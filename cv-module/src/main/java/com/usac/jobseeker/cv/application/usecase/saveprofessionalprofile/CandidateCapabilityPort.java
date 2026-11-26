package com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile;

import com.usac.jobseeker.common.domain.model.AccountId;

/**
 * Puerto de salida: ¿la cuenta tiene habilitada la capacidad de candidato? La capacidad es de
 * `auth`; `cv` solo la pregunta, y la respuesta decide si un cambio del perfil interesa a
 * `matching` (un reclutador que edita su historial no dispara recálculos de un score que nadie
 * leerá).
 */
public interface CandidateCapabilityPort {

  boolean isCandidate(AccountId accountId);

  /**
   * Cuáles de estas cuentas tienen la capacidad. La implementación por defecto pregunta una a una;
   * el adaptador real resuelve todas con una consulta (TD-039).
   */
  default java.util.Set<AccountId> candidatesAmong(java.util.Collection<AccountId> accountIds) {
    java.util.Set<AccountId> candidates = new java.util.HashSet<>();
    for (AccountId id : accountIds) {
      if (isCandidate(id)) {
        candidates.add(id);
      }
    }
    return candidates;
  }
}
