package com.usac.jobseeker.cv.application.usecase.getmyprofilephoto;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

public record GetMyProfilePhotoQuery(AccountId accountId) {
  public GetMyProfilePhotoQuery {
    Objects.requireNonNull(accountId, "accountId");
  }
}
