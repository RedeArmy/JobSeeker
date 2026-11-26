package com.usac.jobseeker.cv.application.usecase.changediscoverability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.error.AlreadyExistsException;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.application.usecase.changeavailability.AvailabilityStorePort;
import com.usac.jobseeker.cv.application.usecase.changeavailability.ChangeAvailabilityCommand;
import com.usac.jobseeker.cv.application.usecase.changeavailability.ChangeAvailabilityService;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateCollectionsReadPort;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileReadPort;
import com.usac.jobseeker.cv.application.usecase.getvisibleavailability.AvailabilityExclusionLookupPort;
import com.usac.jobseeker.cv.application.usecase.getvisibleavailability.GetVisibleAvailabilityQuery;
import com.usac.jobseeker.cv.application.usecase.getvisibleavailability.GetVisibleAvailabilityService;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.AddAvailabilityExclusionCommand;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.AvailabilityExclusionStorePort;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.ListAvailabilityExclusionsQuery;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.ManageAvailabilityExclusionsService;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.RemoveAvailabilityExclusionCommand;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.CandidateProfileStorePort;
import com.usac.jobseeker.cv.domain.model.AvailabilityExclusion;
import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;
import com.usac.jobseeker.cv.domain.model.CandidateCollections;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Descubribilidad, disponibilidad, lista de exclusión y su consulta de visibilidad. */
class VisibilityServicesTest {

  private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

  /** Un doble para el perfil y la lista, como lo es la base de datos. */
  private static final class Fake
      implements CandidateProfileStorePort,
          CandidateProfileReadPort,
          CandidateCollectionsReadPort,
          DiscoverabilityStorePort,
          AvailabilityStorePort,
          AvailabilityExclusionStorePort,
          AvailabilityExclusionLookupPort {
    CandidateProfile profile;
    final Map<AccountId, AvailabilityExclusion> excluded = new LinkedHashMap<>();

    @Override
    public Optional<CandidateProfile> findByAccountId(AccountId accountId) {
      return Optional.ofNullable(profile).filter(p -> p.accountId().equals(accountId));
    }

    @Override
    public boolean insertIfAbsent(CandidateProfile candidate) {
      return false;
    }

    @Override
    public boolean updateIfVersion(CandidateProfile candidate, long expectedVersion) {
      return write(candidate, expectedVersion);
    }

    @Override
    public boolean updateDiscoverabilityIfVersion(
        CandidateProfile candidate, long expectedVersion) {
      return write(candidate, expectedVersion);
    }

    @Override
    public boolean updateAvailabilityIfVersion(CandidateProfile candidate, long expectedVersion) {
      return write(candidate, expectedVersion);
    }

    private boolean write(CandidateProfile candidate, long expectedVersion) {
      if (profile == null || profile.version() != expectedVersion) {
        return false;
      }
      profile = candidate;
      return true;
    }

    @Override
    public CandidateCollections findByProfileId(CandidateProfileId profileId) {
      return CandidateCollections.empty();
    }

    @Override
    public List<AvailabilityExclusion> list(CandidateProfileId profileId) {
      return new ArrayList<>(excluded.values());
    }

    @Override
    public int count(CandidateProfileId profileId) {
      return excluded.size();
    }

    @Override
    public boolean insert(CandidateProfileId profileId, AvailabilityExclusion exclusion) {
      return excluded.putIfAbsent(exclusion.excludedAccount(), exclusion) == null;
    }

    @Override
    public boolean delete(CandidateProfileId profileId, AccountId excludedAccount) {
      return excluded.remove(excludedAccount) != null;
    }

    @Override
    public boolean isExcluded(CandidateProfileId profileId, AccountId account) {
      return excluded.containsKey(account);
    }
  }

  private final AccountId me = new AccountId(UUID.randomUUID());
  private final AccountId other = new AccountId(UUID.randomUUID());
  private final Fake db = new Fake();
  private final List<OutboxEvent> events = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private boolean isCandidate = true;
  private boolean otherExists = true;

  private final CandidateChange change =
      new CandidateChange(
          db,
          db,
          a -> isCandidate,
          events::add,
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome),
          Clock.fixed(NOW, ZoneOffset.UTC),
          UUID::randomUUID);
  private final ChangeDiscoverabilityService discoverability =
      new ChangeDiscoverabilityService(db, change);
  private final ChangeAvailabilityService availability = new ChangeAvailabilityService(db, change);
  private final ManageAvailabilityExclusionsService exclusions =
      new ManageAvailabilityExclusionsService(
          db,
          a -> otherExists,
          change,
          Clock.fixed(NOW, ZoneOffset.UTC),
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome));
  private final GetVisibleAvailabilityService visible = new GetVisibleAvailabilityService(db, db);

  private void aProfile() {
    db.profile =
        CandidateProfile.create(
            new CandidateProfileId(UUID.randomUUID()),
            me,
            CandidateDetails.of("a@b.co", null, null, null, null, null, null, null, null, null),
            NOW);
  }

  // ---- descubribilidad ----

  @Test
  void switchingDiscoverabilityOffAdvancesTheVersionAndEmitsOneEventWithoutTheValue() {
    aProfile();

    var view = discoverability.execute(new ChangeDiscoverabilityCommand(me, false, 1));

    assertThat(view.discoverable()).isFalse();
    assertThat(view.version()).isEqualTo(2);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).eventType()).isEqualTo("candidate.discoverability_changed");
    assertThat(events.get(0).payload()).doesNotContain("false", "true");
    assertThat(metrics).containsExactly("change_discoverability:changed");
  }

  @Test
  void askingForTheValueItAlreadyHasChangesNothingAndEmitsNothing() {
    aProfile();

    var view = discoverability.execute(new ChangeDiscoverabilityCommand(me, true, 1));

    assertThat(view.version()).isEqualTo(1);
    assertThat(events).isEmpty();
    assertThat(metrics).containsExactly("change_discoverability:unchanged");
  }

  @Test
  void aStaleVersionNoProfileOrNoCapabilityAreRejectedAndNothingIsWritten() {
    var noProfile = new ChangeDiscoverabilityCommand(me, false, 1);
    assertThatThrownBy(() -> discoverability.execute(noProfile))
        .isInstanceOf(NotFoundException.class);
    aProfile();
    var stale = new ChangeDiscoverabilityCommand(me, false, 9);
    assertThatThrownBy(() -> discoverability.execute(stale))
        .isInstanceOf(StaleStateException.class);
    isCandidate = false;
    var current = new ChangeDiscoverabilityCommand(me, false, 1);
    assertThatThrownBy(() -> discoverability.execute(current))
        .isInstanceOf(ForbiddenCapabilityException.class);

    assertThat(db.profile.discoverable()).isTrue();
    assertThat(db.profile.version()).isEqualTo(1);
    assertThat(events).isEmpty();
  }

  @Test
  void aWriteThatLosesTheRaceIsStaleEvenIfTheReadSawTheExpectedVersion() {
    aProfile();
    var raced = new ChangeDiscoverabilityService((profile, expected) -> false, change);

    var command = new ChangeDiscoverabilityCommand(me, false, 1);

    assertThatThrownBy(() -> raced.execute(command)).isInstanceOf(StaleStateException.class);
    assertThat(events).isEmpty();
  }

  // ---- disponibilidad ----

  @Test
  void declaringAvailabilityStoresItAndEmitsAnEventWithoutTheStatus() {
    aProfile();

    var view =
        availability.execute(new ChangeAvailabilityCommand(me, AvailabilityStatus.OPEN_TO_WORK, 1));
    var back = availability.execute(new ChangeAvailabilityCommand(me, AvailabilityStatus.UNSET, 2));

    assertThat(view.availability()).isEqualTo(AvailabilityStatus.OPEN_TO_WORK);
    assertThat(back.availability()).isEqualTo(AvailabilityStatus.UNSET);
    assertThat(back.version()).isEqualTo(3);
    assertThat(events)
        .hasSize(2)
        .allSatisfy(e -> assertThat(e.payload()).doesNotContain("OPEN_TO_WORK", "UNSET"));
  }

  @Test
  void anAbsentStatusIsRejectedByTheCommandAndTheSameStatusChangesNothing() {
    assertThatThrownBy(() -> new ChangeAvailabilityCommand(me, null, 1))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(() -> new ChangeAvailabilityCommand(me, AvailabilityStatus.UNSET, -1))
        .isInstanceOf(ValidationFailedException.class);
    aProfile();

    var view = availability.execute(new ChangeAvailabilityCommand(me, AvailabilityStatus.UNSET, 1));

    assertThat(view.version()).isEqualTo(1);
    assertThat(events).isEmpty();
  }

  // ---- lista de exclusión ----

  @Test
  void addingAndRemovingAnAccountEmitsAnEventEachAndNeverTouchesTheProfileVersion() {
    aProfile();

    var added = exclusions.add(new AddAvailabilityExclusionCommand(me, other));
    var removed = exclusions.remove(new RemoveAvailabilityExclusionCommand(me, other));

    assertThat(added).hasSize(1);
    assertThat(added.get(0).accountId()).isEqualTo(other.value());
    assertThat(removed).isEmpty();
    assertThat(db.profile.version()).isEqualTo(1);
    assertThat(events)
        .extracting(OutboxEvent::eventType)
        .containsOnly("candidate.availability_exclusion_changed");
    assertThat(events.get(0).payload()).contains("added");
    assertThat(events.get(1).payload()).contains("removed");
    assertThat(metrics)
        .containsExactly(
            "add_availability_exclusion:added", "remove_availability_exclusion:removed");
  }

  @Test
  void aDuplicateASelfExclusionAnUnknownAccountAndAMissingOneAreRejected() {
    aProfile();
    exclusions.add(new AddAvailabilityExclusionCommand(me, other));
    events.clear();
    var again = new AddAvailabilityExclusionCommand(me, other);
    var self = new AddAvailabilityExclusionCommand(me, me);
    otherExists = false;
    var unknown = new AddAvailabilityExclusionCommand(me, new AccountId(UUID.randomUUID()));
    var absent = new RemoveAvailabilityExclusionCommand(me, new AccountId(UUID.randomUUID()));

    otherExists = true;
    assertThatThrownBy(() -> exclusions.add(again)).isInstanceOf(AlreadyExistsException.class);
    assertThatThrownBy(() -> exclusions.add(self)).isInstanceOf(ValidationFailedException.class);
    otherExists = false;
    assertThatThrownBy(() -> exclusions.add(unknown)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> exclusions.remove(absent)).isInstanceOf(NotFoundException.class);

    assertThat(db.excluded).hasSize(1);
    assertThat(events).isEmpty();
  }

  @Test
  void theListHasAnUpperLimit() {
    aProfile();
    for (int i = 0; i < AvailabilityExclusion.MAX_PER_CANDIDATE; i++) {
      var account = new AccountId(UUID.randomUUID());
      db.excluded.put(account, new AvailabilityExclusion(account, NOW));
    }
    var one = new AddAvailabilityExclusionCommand(me, other);

    assertThatThrownBy(() -> exclusions.add(one)).isInstanceOf(ValidationFailedException.class);

    assertThat(metrics).containsExactly("add_availability_exclusion:invalid");
  }

  @Test
  void everyOperationNeedsTheCapabilityAndAProfile() {
    var list = new ListAvailabilityExclusionsQuery(me);
    var add = new AddAvailabilityExclusionCommand(me, other);
    var remove = new RemoveAvailabilityExclusionCommand(me, other);
    assertThatThrownBy(() -> exclusions.list(list)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> exclusions.add(add)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> exclusions.remove(remove)).isInstanceOf(NotFoundException.class);
    aProfile();
    isCandidate = false;

    assertThatThrownBy(() -> exclusions.list(list))
        .isInstanceOf(ForbiddenCapabilityException.class);
    assertThatThrownBy(() -> exclusions.add(add)).isInstanceOf(ForbiddenCapabilityException.class);
    assertThatThrownBy(() -> exclusions.remove(remove))
        .isInstanceOf(ForbiddenCapabilityException.class);
  }

  // ---- qué estado ve cada cuenta (BR-CAND-05, BR-VIS-06) ----

  @Test
  void anExcludedAccountSeesUnsetWhileEveryoneElseAndTheOwnerSeeTheRealStatus() {
    aProfile();
    availability.execute(new ChangeAvailabilityCommand(me, AvailabilityStatus.OPEN_TO_WORK, 1));
    exclusions.add(new AddAvailabilityExclusionCommand(me, other));
    var stranger = new AccountId(UUID.randomUUID());

    assertThat(visible.execute(new GetVisibleAvailabilityQuery(me, other))).isEqualTo("UNSET");
    assertThat(visible.execute(new GetVisibleAvailabilityQuery(me, stranger)))
        .isEqualTo("OPEN_TO_WORK");
    assertThat(visible.execute(new GetVisibleAvailabilityQuery(me, me))).isEqualTo("OPEN_TO_WORK");
  }

  @Test
  void aCandidateWithoutProfileIsUnsetForEveryone() {
    assertThat(visible.execute(new GetVisibleAvailabilityQuery(me, other))).isEqualTo("UNSET");
  }
}
