package com.usac.jobseeker.cv.application.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.InMemoryProfessionalProfiles;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Las cuatro colecciones del perfil deben comportarse **igual**: la misma regla de versión, de
 * evento, de propiedad del elemento y de límite. Una sola batería las recorre a todas, de modo que
 * una diferencia entre ellas no puede pasar desapercibida.
 */
class ManageCollectionsServicesTest {

  /** Todo lo que una prueba necesita, nuevo en cada ejecución. */
  static final class Fixture {
    final AccountId account = new AccountId(UUID.randomUUID());
    final InMemoryProfessionalProfiles profiles = new InMemoryProfessionalProfiles();
    final InMemoryItems items = new InMemoryItems();
    final List<OutboxEvent> events = new ArrayList<>();
    final List<String> metrics = new ArrayList<>();
    final AtomicInteger sequence = new AtomicInteger();
    boolean candidate = true;
    final ProfileChange change =
        new ProfileChange(
            profiles,
            items,
            id -> candidate,
            events::add,
            (useCase, outcome) -> metrics.add(useCase + ":" + outcome),
            Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC),
            () -> new UUID(0, sequence.incrementAndGet()));

    ProfessionalProfile createProfile(AccountId owner) {
      var profile =
          ProfessionalProfile.create(
              new ProfessionalProfileId(UUID.randomUUID()),
              owner,
              ProfessionalIdentity.of("Ana", null, null),
              Instant.parse("2026-11-23T09:00:00Z"));
      profiles.insertIfAbsent(profile);
      return profile;
    }

    /**
     * Ejecuta una operación como lo haría `@Transactional`: si falla, el estado vuelve a como
     * estaba, incluido el avance de versión que se hizo antes de descubrir que el elemento no
     * existe. El doble en memoria no revierte por sí solo; la reversión real la comprueba la prueba
     * de extremo a extremo contra Postgres.
     */
    <T> T transactionally(java.util.function.Supplier<T> operation) {
      var before = profiles.snapshot();
      try {
        return operation.get();
      } catch (RuntimeException failure) {
        profiles.restore(before);
        throw failure;
      }
    }

    long versionOf(AccountId owner) {
      return profiles.findByAccountId(owner).orElseThrow().version();
    }
  }

  static Stream<Arguments> collections() {
    return Stream.of(
        scenario("experiencias", CollectionScenario::experiences),
        scenario("formación", CollectionScenario::education),
        scenario("certificaciones", CollectionScenario::certifications),
        scenario("proyectos", CollectionScenario::projects));
  }

  private static Arguments scenario(String name, Factory factory) {
    return Arguments.of(Named.of(name, factory));
  }

  @FunctionalInterface
  interface Factory extends Function<Fixture, CollectionScenario> {
    CollectionScenario create(ProfileChange change, InMemoryItems items);

    @Override
    default CollectionScenario apply(Fixture f) {
      return create(f.change, f.items);
    }
  }

  // ---- alta ----

  @ParameterizedTest
  @MethodSource("collections")
  void addingAdvancesTheVersionReturnsTheWholeProfileAndCountsIt(Factory factory) {
    var f = new Fixture();
    var s = factory.apply(f);
    f.createProfile(f.account);

    ProfessionalProfileView view = s.add(f.account, 1);

    assertThat(view.version()).isEqualTo(2);
    assertThat(s.itemIds(view)).hasSize(1);
    assertThat(f.versionOf(f.account)).isEqualTo(2);
    assertThat(f.metrics).containsExactly(s.useCase() + ":added");
  }

  @ParameterizedTest
  @MethodSource("collections")
  void aCandidateAccountPublishesTheEventWithTheKindOfChangeAndOnlyIdentifiers(Factory factory) {
    var f = new Fixture();
    var s = factory.apply(f);
    var root = f.createProfile(f.account);

    s.add(f.account, 1);

    assertThat(f.events)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.eventType()).isEqualTo("profile.updated");
              assertThat(e.aggregateId()).isEqualTo(root.id().value());
              assertThat(e.aggregateVersion()).isEqualTo(2);
              assertThat(e.payload())
                  .isEqualTo(
                      "{\"accountId\":\""
                          + f.account
                          + "\",\"change\":\""
                          + s.changeKind()
                          + "\",\"version\":2}");
            });
  }

  @ParameterizedTest
  @MethodSource("collections")
  void anAccountWithoutTheCandidateCapabilityChangesItsProfileButPublishesNothing(Factory factory) {
    var f = new Fixture();
    f.candidate = false;
    var s = factory.apply(f);
    f.createProfile(f.account);

    s.add(f.account, 1);

    assertThat(f.events).isEmpty();
    assertThat(f.versionOf(f.account)).isEqualTo(2);
  }

  // ---- reemplazo y baja ----

  @ParameterizedTest
  @MethodSource("collections")
  void updatingReplacesTheItemAndAdvancesTheVersion(Factory factory) {
    var f = new Fixture();
    var s = factory.apply(f);
    f.createProfile(f.account);
    var created = s.add(f.account, 1);
    UUID id = s.itemIds(created).get(0);

    var updated = s.update(f.account, created.version(), id, "Nuevo nombre");

    assertThat(updated.version()).isEqualTo(3);
    assertThat(s.itemIds(updated)).containsExactly(id);
    assertThat(s.labelOf(updated, id)).isEqualTo("Nuevo nombre");
    assertThat(f.metrics).containsExactly(s.useCase() + ":added", s.useCase() + ":updated");
  }

  @ParameterizedTest
  @MethodSource("collections")
  void removingTakesTheItemOutAndAdvancesTheVersion(Factory factory) {
    var f = new Fixture();
    var s = factory.apply(f);
    f.createProfile(f.account);
    var created = s.add(f.account, 1);

    var removed = s.remove(f.account, created.version(), s.itemIds(created).get(0));

    assertThat(s.itemIds(removed)).isEmpty();
    assertThat(removed.version()).isEqualTo(3);
    assertThat(f.metrics).endsWith(s.useCase() + ":removed");
  }

  // ---- versión ----

  @ParameterizedTest
  @MethodSource("collections")
  void aStaleVersionIsRejectedAndNothingIsWrittenOrPublished(Factory factory) {
    var f = new Fixture();
    var s = factory.apply(f);
    f.createProfile(f.account);

    assertThatThrownBy(() -> s.add(f.account, 7)).isInstanceOf(StaleStateException.class);

    assertThat(f.versionOf(f.account)).isEqualTo(1);
    assertThat(
            f.items
                .findByProfileId(f.profiles.findByAccountId(f.account).orElseThrow().id())
                .experiences())
        .isEmpty();
    assertThat(f.events).isEmpty();
    assertThat(f.metrics).containsExactly(s.useCase() + ":stale");
  }

  @ParameterizedTest
  @MethodSource("collections")
  void theSecondEditWithTheSameVersionLoses(Factory factory) {
    var f = new Fixture();
    var s = factory.apply(f);
    f.createProfile(f.account);
    s.add(f.account, 1);

    assertThatThrownBy(() -> s.add(f.account, 1)).isInstanceOf(StaleStateException.class);
  }

  // ---- perfil o elemento inexistente ----

  @ParameterizedTest
  @MethodSource("collections")
  void anAccountWithoutAProfileCannotEditAnything(Factory factory) {
    var f = new Fixture();
    var s = factory.apply(f);

    assertThatThrownBy(() -> s.add(f.account, 0)).isInstanceOf(NotFoundException.class);
    assertThat(f.metrics).containsExactly(s.useCase() + ":not_found");
  }

  @ParameterizedTest
  @MethodSource("collections")
  void anItemThatDoesNotExistIsNotFoundForUpdateAndForRemoval(Factory factory) {
    var f = new Fixture();
    var s = factory.apply(f);
    f.createProfile(f.account);
    UUID unknown = UUID.randomUUID();

    Supplier<Object> updating = () -> s.update(f.account, 1, unknown, "X");
    Supplier<Object> removing = () -> s.remove(f.account, 1, unknown);

    assertThatThrownBy(() -> f.transactionally(updating)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> f.transactionally(removing)).isInstanceOf(NotFoundException.class);
    assertThat(f.versionOf(f.account)).as("la versión no avanzó").isEqualTo(1);
  }

  @ParameterizedTest
  @MethodSource("collections")
  void anItemOfAnotherProfileIsNotFoundAndIsLeftUntouched(Factory factory) {
    var f = new Fixture();
    var s = factory.apply(f);
    AccountId other = new AccountId(UUID.randomUUID());
    f.createProfile(f.account);
    f.createProfile(other);
    var theirs = s.add(other, 1);
    UUID theirItem = s.itemIds(theirs).get(0);

    Supplier<Object> updating = () -> s.update(f.account, 1, theirItem, "Robado");
    Supplier<Object> removing = () -> s.remove(f.account, 1, theirItem);

    assertThatThrownBy(() -> f.transactionally(updating)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> f.transactionally(removing)).isInstanceOf(NotFoundException.class);

    var after = s.update(other, theirs.version(), theirItem, "Sigue siendo suyo");
    assertThat(s.labelOf(after, theirItem)).isEqualTo("Sigue siendo suyo");
  }

  // ---- validación ----

  @ParameterizedTest
  @MethodSource("collections")
  void invalidDataIsRejectedBeforeTouchingTheVersionAndCounted(Factory factory) {
    var f = new Fixture();
    var s = factory.apply(f);
    f.createProfile(f.account);

    assertThatThrownBy(() -> s.addInvalid(f.account, 1))
        .isInstanceOf(ValidationFailedException.class);

    assertThat(f.versionOf(f.account)).isEqualTo(1);
    assertThat(f.events).isEmpty();
    assertThat(f.metrics).containsExactly(s.useCase() + ":invalid");
  }

  @ParameterizedTest
  @MethodSource("collections")
  void invalidDataWinsOverAStaleVersionBecauseItIsCheckedFirst(Factory factory) {
    var f = new Fixture();
    var s = factory.apply(f);
    f.createProfile(f.account);

    assertThatThrownBy(() -> s.addInvalid(f.account, 99))
        .isInstanceOf(ValidationFailedException.class);
  }

  @ParameterizedTest
  @MethodSource("collections")
  void aCollectionAcceptsFiftyElementsAndRefusesTheNext(Factory factory) {
    var f = new Fixture();
    var s = factory.apply(f);
    f.createProfile(f.account);
    long version = 1;
    for (int i = 0; i < 50; i++) {
      version = s.add(f.account, version).version();
    }
    long full = version;

    assertThatThrownBy(() -> s.add(f.account, full))
        .isInstanceOfSatisfying(
            ValidationFailedException.class,
            e ->
                assertThat(e.violations())
                    .singleElement()
                    .satisfies(v -> assertThat(v.code()).isEqualTo("TOO_MANY")));
    assertThat(f.metrics).last().isEqualTo(s.useCase() + ":invalid");
  }

  @ParameterizedTest
  @MethodSource("collections")
  void aNegativeVersionIsInvalidFromTheCommandItself(Factory factory) {
    var f = new Fixture();
    var s = factory.apply(f);

    UUID item = UUID.randomUUID();

    assertThatThrownBy(() -> s.add(f.account, -1)).isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(() -> s.remove(f.account, -1, item))
        .isInstanceOf(ValidationFailedException.class);
  }
}
