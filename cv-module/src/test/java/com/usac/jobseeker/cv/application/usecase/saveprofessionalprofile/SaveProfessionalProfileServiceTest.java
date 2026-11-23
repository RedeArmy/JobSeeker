package com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfileCollectionsReadPort;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfileCollections;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SaveProfessionalProfileServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final InMemoryProfessionalProfiles store = new InMemoryProfessionalProfiles();
  private final List<OutboxEvent> events = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private final AtomicInteger sequence = new AtomicInteger();
  private boolean candidate = true;
  private ProfessionalProfileStorePort port = store;

  private SaveProfessionalProfileService service() {
    return new SaveProfessionalProfileService(
        port,
        profileId -> ProfileCollections.empty(),
        id -> candidate,
        events::add,
        (useCase, outcome) -> metrics.add(useCase + ":" + outcome),
        Clock.fixed(NOW, ZoneOffset.UTC),
        () -> new UUID(0, sequence.incrementAndGet()));
  }

  private SaveProfessionalProfileResult save(String name, long expectedVersion) {
    return service()
        .execute(new SaveProfessionalProfileCommand(account, name, "Dev", "Bio", expectedVersion));
  }

  @Test
  void theFirstSaveCreatesTheProfileAtVersionOne() {
    var result = save("Ana López", 0);

    assertThat(result.created()).isTrue();
    assertThat(result.profile().version()).isEqualTo(1);
    assertThat(result.profile().fullName()).isEqualTo("Ana López");
    assertThat(result.profile().updatedAt()).isEqualTo(NOW);
    assertThat(store.findByAccountId(account)).isPresent();
    assertThat(metrics).containsExactly("save_professional_profile:created");
  }

  @Test
  void aCandidateAccountPublishesTheEventWithOnlyIdentifiers() {
    var result = save("Ana López", 0);

    assertThat(events)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.eventType()).isEqualTo("profile.updated");
              assertThat(e.aggregateType()).isEqualTo("professional_profile");
              assertThat(e.aggregateId()).isEqualTo(result.profile().id());
              assertThat(e.aggregateVersion()).isEqualTo(1);
              assertThat(e.payload())
                  .isEqualTo(
                      "{\"accountId\":\"" + account + "\",\"change\":\"identity\",\"version\":1}");
              assertThat(e.payload()).doesNotContain("Ana", "Dev", "Bio");
            });
  }

  @Test
  void anAccountWithoutTheCandidateCapabilityPublishesNothing() {
    candidate = false;

    save("Ana López", 0);

    assertThat(events).isEmpty();
    assertThat(store.findByAccountId(account)).isPresent();
  }

  @Test
  void anEditWithTheCurrentVersionWritesTheNextOneAndPublishesIt() {
    save("Ana", 0);
    events.clear();

    var result = save("Ana López", 1);

    assertThat(result.created()).isFalse();
    assertThat(result.profile().version()).isEqualTo(2);
    assertThat(store.findByAccountId(account))
        .get()
        .extracting(p -> p.identity().fullName())
        .isEqualTo("Ana López");
    assertThat(events)
        .singleElement()
        .satisfies(e -> assertThat(e.aggregateVersion()).isEqualTo(2));
    assertThat(metrics).endsWith("save_professional_profile:updated");
  }

  @Test
  void savingTheSameDataAtTheCurrentVersionChangesNothingAndNotifiesNobody() {
    save("Ana", 0);
    events.clear();
    metrics.clear();

    var result = save("Ana", 1);

    assertThat(result.created()).isFalse();
    assertThat(result.profile().version()).isEqualTo(1);
    assertThat(events).isEmpty();
    assertThat(metrics).containsExactly("save_professional_profile:unchanged");
  }

  @Test
  void theSameDataWithAStaleVersionIsStillAConflict() {
    save("Ana", 0);
    save("Ana López", 1);

    assertThatThrownBy(() -> save("Ana López", 1)).isInstanceOf(StaleStateException.class);
  }

  @Test
  void aStaleVersionIsRejectedAndNothingIsWrittenOrPublished() {
    save("Ana", 0);
    events.clear();
    metrics.clear();

    assertThatThrownBy(() -> save("Otra", 7)).isInstanceOf(StaleStateException.class);

    assertThat(store.findByAccountId(account))
        .get()
        .extracting(p -> p.identity().fullName())
        .isEqualTo("Ana");
    assertThat(events).isEmpty();
    assertThat(metrics).containsExactly("save_professional_profile:stale");
  }

  @Test
  void creatingWithAVersionOtherThanZeroIsAConflict() {
    assertThatThrownBy(() -> save("Ana", 3)).isInstanceOf(StaleStateException.class);

    assertThat(store.size()).isZero();
    assertThat(events).isEmpty();
  }

  @Test
  void creatingWhenTheProfileAlreadyExistsIsAConflict() {
    save("Ana", 0);

    assertThatThrownBy(() -> save("Otra", 0)).isInstanceOf(StaleStateException.class);

    assertThat(store.findByAccountId(account))
        .get()
        .extracting(p -> p.identity().fullName())
        .isEqualTo("Ana");
  }

  @Test
  void losingTheCreationRaceIsAConflictAndPublishesNothing() {
    port =
        new ProfessionalProfileStorePort() {
          @Override
          public java.util.Optional<ProfessionalProfile> findByAccountId(AccountId a) {
            return java.util.Optional.empty();
          }

          @Override
          public boolean insertIfAbsent(ProfessionalProfile profile) {
            return false;
          }

          @Override
          public boolean updateIfVersion(ProfessionalProfile profile, long expectedVersion) {
            throw new AssertionError("no debe actualizar");
          }
        };

    assertThatThrownBy(() -> save("Ana", 0)).isInstanceOf(StaleStateException.class);

    assertThat(events).isEmpty();
    assertThat(metrics).containsExactly("save_professional_profile:stale");
  }

  @Test
  void losingTheUpdateRaceIsAConflictAndPublishesNothing() {
    save("Ana", 0);
    events.clear();
    metrics.clear();
    ProfessionalProfileStorePort real = store;
    port =
        new ProfessionalProfileStorePort() {
          @Override
          public java.util.Optional<ProfessionalProfile> findByAccountId(AccountId a) {
            return real.findByAccountId(a);
          }

          @Override
          public boolean insertIfAbsent(ProfessionalProfile profile) {
            throw new AssertionError("no debe insertar");
          }

          @Override
          public boolean updateIfVersion(ProfessionalProfile profile, long expectedVersion) {
            return false;
          }
        };

    assertThatThrownBy(() -> save("Otra", 1)).isInstanceOf(StaleStateException.class);

    assertThat(events).isEmpty();
    assertThat(metrics).containsExactly("save_professional_profile:stale");
  }

  @Test
  void invalidDataIsRejectedBeforeTouchingTheStoreAndCounted() {
    assertThatThrownBy(() -> save("", 0)).isInstanceOf(ValidationFailedException.class);

    assertThat(store.size()).isZero();
    assertThat(events).isEmpty();
    assertThat(metrics).containsExactly("save_professional_profile:invalid");
  }

  @Test
  void aNegativeVersionIsInvalidFromTheCommandItself() {
    assertThatThrownBy(() -> new SaveProfessionalProfileCommand(account, "Ana", null, null, -1))
        .isInstanceOf(ValidationFailedException.class)
        .satisfies(
            e ->
                assertThat(((ValidationFailedException) e).violations())
                    .singleElement()
                    .satisfies(v -> assertThat(v.field()).isEqualTo("version")));
  }

  @Test
  void everyCollaboratorIsRequired() {
    Clock clock = Clock.systemUTC();
    ProfileCollectionsReadPort none = profileId -> ProfileCollections.empty();
    CandidateCapabilityPort cap = id -> true;
    DomainEventPublisherPort publisher = events::add;
    UseCaseMetricsPort counter = (a, b) -> {};
    Supplier<UUID> ids = UUID::randomUUID;

    assertThatThrownBy(
            () ->
                new SaveProfessionalProfileService(null, none, cap, publisher, counter, clock, ids))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new SaveProfessionalProfileService(
                    store, null, cap, publisher, counter, clock, ids))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new SaveProfessionalProfileService(
                    store, none, null, publisher, counter, clock, ids))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new SaveProfessionalProfileService(store, none, cap, null, counter, clock, ids))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new SaveProfessionalProfileService(store, none, cap, publisher, null, clock, ids))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new SaveProfessionalProfileService(store, none, cap, publisher, counter, null, ids))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new SaveProfessionalProfileService(
                    store, none, cap, publisher, counter, clock, null))
        .isInstanceOf(NullPointerException.class);
  }
}
