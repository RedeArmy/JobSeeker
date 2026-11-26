package com.usac.jobseeker.cv.application.usecase.getvisibleavailability;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileReadPort;
import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Una cuenta excluida ve
 * `UNSET`, no un error ni un estado distinto: no debe poder notar que está excluida (BR-VIS-06).
 */
public class GetVisibleAvailabilityService
    implements GetVisibleAvailabilityUseCase, GetVisibleAvailabilitiesUseCase {

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

  // La misma regla que la individual, con una consulta de perfiles y otra de exclusiones (TD-039).
  @Override
  @Transactional(readOnly = true)
  public Map<AccountId, String> execute(GetVisibleAvailabilitiesQuery query) {
    Map<AccountId, CandidateProfile> found = profiles.findAllByAccountIds(query.candidates());
    Set<CandidateProfileId> excluded =
        exclusions.excludedAmong(
            found.values().stream().map(CandidateProfile::id).toList(), query.viewer());
    Map<AccountId, String> visible = new HashMap<>();
    for (AccountId candidate : query.candidates()) {
      CandidateProfile profile = found.get(candidate);
      boolean owner = candidate.equals(query.viewer());
      visible.put(
          candidate,
          profile == null || (!owner && excluded.contains(profile.id()))
              ? AvailabilityStatus.UNSET.name()
              : profile.availability().name());
    }
    return visible;
  }

  private AvailabilityStatus visibleTo(
      CandidateProfile profile, GetVisibleAvailabilityQuery query) {
    boolean owner = query.candidate().equals(query.viewer());
    return !owner && exclusions.isExcluded(profile.id(), query.viewer())
        ? AvailabilityStatus.UNSET
        : profile.availability();
  }
}
