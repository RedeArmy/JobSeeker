package com.usac.jobseeker.matching.application.usecase.explainscore;

import com.usac.jobseeker.common.domain.model.AccountId;

/**
 * Puerto de salida: ¿esta cuenta puede actuar como candidata? La capacidad es de `auth`
 * (BR-ACCT-01); `matching` solo la pregunta. Una cuenta que no puede actuar se trata como sin
 * capacidad.
 */
public interface CandidateAccessPort {

  boolean isCandidate(AccountId account);
}
