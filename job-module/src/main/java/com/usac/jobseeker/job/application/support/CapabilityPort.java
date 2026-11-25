package com.usac.jobseeker.job.application.support;

import com.usac.jobseeker.common.domain.model.AccountId;

/**
 * Puerto de salida: ¿qué capacidades tiene la cuenta? Son de `auth` y `job` solo las pregunta:
 * quien publica es reclutador (BR-JOB-01) y quien ve una vacante ajena es candidato (BR-ACCT-04).
 * Una cuenta que no puede actuar se trata como sin capacidad.
 */
public interface CapabilityPort {

  boolean isRecruiter(AccountId accountId);

  boolean isCandidate(AccountId accountId);
}
