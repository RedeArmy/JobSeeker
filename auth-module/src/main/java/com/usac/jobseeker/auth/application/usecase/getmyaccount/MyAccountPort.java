package com.usac.jobseeker.auth.application.usecase.getmyaccount;

import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Optional;

/** Puerto de salida de GetMyAccount: la cuenta local, o vacío si no existe. */
public interface MyAccountPort {

  Optional<Account> findById(AccountId id);
}
