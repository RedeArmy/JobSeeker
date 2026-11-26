package com.usac.jobseeker.cv.application.usecase.getvisibleavailability;

import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileReadPort;
import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Una cuenta excluida ve
 * `UNSET`, no un error ni un estado distinto: no debe poder notar que está excluida (BR-VIS-06).
 */
public class GetVisibleAvailabilityService implements GetVisibleAvailabilityUseCase {

  private final CandidateProfileReadPort profiles;
  private final AvailabilityExclusionLookupPort exclusions;

  public GetVisibleAvailabilityService(
      CandidateProfileReadPort profiles, AvailabilityExclusionLookupPort exclusions) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.exclusions = Objects.requireNonNull(exclusions, "exclusions");
  }

  @Override
  @Transactional(readOnly = true)
  public String execute(GetVisibleAvailabilityQuery query) {
    return profiles
        .findByAccountId(query.candidate())
        .map(profile -> visibleTo(profile, query))
        .orElse(AvailabilityStatus.UNSET)
        .name();
  }

  private AvailabilityStatus visibleTo(
      CandidateProfile profile, GetVisibleAvailabilityQuery query) {
    boolean owner = query.candidate().equals(query.viewer());
    return !owner && exclusions.isExcluded(profile.id(), query.viewer())
        ? AvailabilityStatus.UNSET
        : profile.availability();
  }
}
