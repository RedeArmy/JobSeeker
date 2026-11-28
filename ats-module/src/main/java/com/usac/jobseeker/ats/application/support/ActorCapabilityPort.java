package com.usac.jobseeker.ats.application.support;

import com.usac.jobseeker.common.domain.model.AccountId;

/**
 * ¿Qué puede hacer la cuenta? Las capacidades son de `auth`, y cada una se comporta como si la
 * cuenta fuera solo de ese rol (BR-ACCT-04). Una cuenta que no puede actuar, como una en periodo de
 * gracia, se trata como sin ninguna: no postula (BR-DATA-04) ni gestiona un pipeline.
 */
public interface ActorCapabilityPort {

  boolean isCandidate(AccountId accountId);

  boolean isRecruiter(AccountId accountId);
}
