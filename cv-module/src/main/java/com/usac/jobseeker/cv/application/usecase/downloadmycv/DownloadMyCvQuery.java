package com.usac.jobseeker.cv.application.usecase.downloadmycv;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;
import java.util.UUID;

public record DownloadMyCvQuery(AccountId accountId, UUID fileId) {
  public DownloadMyCvQuery {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(fileId, "fileId");
  }
}
