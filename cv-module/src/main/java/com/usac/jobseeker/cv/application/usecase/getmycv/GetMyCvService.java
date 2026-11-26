package com.usac.jobseeker.cv.application.usecase.getmycv;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.CandidateProfileStorePort;
import com.usac.jobseeker.cv.application.usecase.uploadcv.CvFilePolicy;
import com.usac.jobseeker.cv.application.usecase.uploadcv.CvFileStorePort;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/** Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). */
public class GetMyCvService implements GetMyCvUseCase {

  private static final String USE_CASE = "get_my_cv";

  private final CandidateChange change;
  private final CandidateProfileStorePort profiles;
  private final CvFileStorePort files;
  private final CvFilePolicy policy;
  private final UseCaseMetricsPort metrics;

  public GetMyCvService(
      CandidateChange change,
      CandidateProfileStorePort profiles,
      CvFileStorePort files,
      CvFilePolicy policy,
      UseCaseMetricsPort metrics) {
    this.change = Objects.requireNonNull(change, "change");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.files = Objects.requireNonNull(files, "files");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public MyCvView execute(GetMyCvQuery query) {
    change.requireCandidate(USE_CASE, query.accountId());
    var profile = profiles.findByAccountId(query.accountId());
    if (profile.isEmpty()) {
      metrics.count(USE_CASE, "no_profile");
      throw new NotFoundException();
    }
    var versions = files.versions(profile.get().id()).stream().map(CvFileView::of).toList();
    metrics.count(USE_CASE, versions.isEmpty() ? "empty" : "found");
    return new MyCvView(
        versions.stream().filter(CvFileView::current).findFirst(), versions, policy.maxVersions());
  }
}
