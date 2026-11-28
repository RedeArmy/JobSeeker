package com.usac.jobseeker.tracker.application.support;

import com.usac.jobseeker.common.domain.model.AccountId;

/** ¿La cuenta puede actuar como candidato? La capacidad es de `auth` (BR-ACCT-04). */
public interface CandidateCapabilityPort {
  boolean isCandidate(AccountId accountId);
}
