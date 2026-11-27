package com.usac.jobseeker.cv.application.usecase.removeprofilephoto;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

public record RemoveProfilePhotoCommand(AccountId accountId) {
  public RemoveProfilePhotoCommand {
    Objects.requireNonNull(accountId, "accountId");
  }
}
