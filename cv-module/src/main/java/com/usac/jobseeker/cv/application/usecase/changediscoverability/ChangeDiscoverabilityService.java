package com.usac.jobseeker.cv.application.usecase.changediscoverability;

import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileView;
import com.usac.jobseeker.cv.domain.event.CandidateDiscoverabilityChanged;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). No recalcula ningún score:
 * el evento solo hace que `matching` actualice su copia de `discoverable` y saque o devuelva al
 * candidato de los sugeridos al instante (BR-DATA-03, BR-VIS-04).
 */
public class ChangeDiscoverabilityService implements ChangeDiscoverabilityUseCase {

  private static final String USE_CASE = "change_discoverability";

  private final DiscoverabilityStorePort store;
  private final CandidateChange change;

  public ChangeDiscoverabilityService(DiscoverabilityStorePort store, CandidateChange change) {
    this.store = Objects.requireNonNull(store, "store");
    this.change = Objects.requireNonNull(change, "change");
  }

  @Override
  @Transactional
  public CandidateProfileView execute(ChangeDiscoverabilityCommand c) {
    return change.editProfile(
        USE_CASE,
        c.accountId(),
        c.expectedVersion(),
        new CandidateChange.ProfileEdit(
            (profile, now) -> profile.withDiscoverable(c.discoverable(), now),
            profile -> profile.discoverable() == c.discoverable(),
            store::updateDiscoverabilityIfVersion,
            CandidateDiscoverabilityChanged.EVENT_TYPE,
            profile ->
                CandidateDiscoverabilityChanged.payload(profile.accountId(), profile.version())));
  }
}
