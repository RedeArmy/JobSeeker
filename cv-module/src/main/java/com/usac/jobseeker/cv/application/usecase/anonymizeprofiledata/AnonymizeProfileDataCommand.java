package com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** La cuenta cuyos datos de `cv` se anonimizan. Es una orden interna del lote de cumplimiento. */
public record AnonymizeProfileDataCommand(AccountId accountId) {

  public AnonymizeProfileDataCommand {
    Objects.requireNonNull(accountId, "accountId");
  }
}
