package com.usac.jobseeker.cv.application.usecase.getmycv;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

public record GetMyCvQuery(AccountId accountId) {
  public GetMyCvQuery {
    Objects.requireNonNull(accountId, "accountId");
  }
}
