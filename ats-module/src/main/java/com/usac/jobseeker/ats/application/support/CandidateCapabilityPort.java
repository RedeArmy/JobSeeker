package com.usac.jobseeker.ats.application.support;

import com.usac.jobseeker.common.domain.model.AccountId;

/**
 * ¿La cuenta puede actuar como candidato? La capacidad es de `auth`; una cuenta en periodo de
 * gracia no puede actuar y se trata como sin capacidad, lo que cumple BR-DATA-04 («no puede
 * postular»).
 */
public interface CandidateCapabilityPort {
  boolean isCandidate(AccountId accountId);
}
