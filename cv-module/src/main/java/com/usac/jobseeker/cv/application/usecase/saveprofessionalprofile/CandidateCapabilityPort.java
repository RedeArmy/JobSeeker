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
}
