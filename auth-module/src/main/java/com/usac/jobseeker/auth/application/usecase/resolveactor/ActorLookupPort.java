package com.usac.jobseeker.auth.application.usecase.resolveactor;

import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Optional;

/** Puerto de salida de ResolveActor: lectura de la cuenta local por su identificador. */
public interface ActorLookupPort {

  Optional<Account> findById(AccountId id);
}
