package com.usac.jobseeker.cv.application.usecase.changeavailability;

import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileView;
import com.usac.jobseeker.cv.domain.event.CandidateAvailabilityChanged;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). El evento puede cambiar el
 * orden de los sugeridos (BR-MATCH-07) pero nunca recalcula un score: la disponibilidad es una
 * señal de intención, no de compatibilidad (BR-MATCH-01). El estado no viaja en el evento.
 */
public class ChangeAvailabilityService implements ChangeAvailabilityUseCase {

  private static final String USE_CASE = "change_availability";

  private final AvailabilityStorePort store;
  private final CandidateChange change;

  public ChangeAvailabilityService(AvailabilityStorePort store, CandidateChange change) {
    this.store = Objects.requireNonNull(store, "store");
    this.change = Objects.requireNonNull(change, "change");
  }

  @Override
  @Transactional
  public CandidateProfileView execute(ChangeAvailabilityCommand c) {
    return change.editProfile(
        USE_CASE,
        c.accountId(),
        c.expectedVersion(),
        new CandidateChange.ProfileEdit(
            (profile, now) -> profile.withAvailability(c.status(), now),
            profile -> profile.availability() == c.status(),
            store::updateAvailabilityIfVersion,
            CandidateAvailabilityChanged.EVENT_TYPE,
            profile ->
                CandidateAvailabilityChanged.payload(profile.accountId(), profile.version())));
  }
}
