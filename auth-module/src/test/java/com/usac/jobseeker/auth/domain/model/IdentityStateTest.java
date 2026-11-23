package com.usac.jobseeker.auth.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * La regla de orden de los eventos de identidad (ADR-0020, superficie C). Lo que importa es que el
 * resultado no dependa del orden de llegada: Clavaris entrega al menos una vez, repetido y
 * desordenado.
 */
class IdentityStateTest {

  private static final Instant T0 = Instant.parse("2026-11-22T12:00:00.000Z");
  private static final UUID LOW = new UUID(0x0000000000000001L, 0x0000000000000001L);
  private static final UUID HIGH = new UUID(0xF000000000000000L, 0L);

  private static Optional<IdentityState> apply(
      IdentityState state, IdentityStatus status, Instant at, UUID id) {
    return state.accept(status, at, id);
  }

  // ---- el primer evento y los posteriores ----

  @Test
  void theInitialStateIsActiveWithoutAnyEvent() {
    IdentityState initial = IdentityState.initial();

    assertThat(initial.status()).isEqualTo(IdentityStatus.ACTIVE);
    assertThat(initial.eventAt()).isNull();
    assertThat(initial.eventId()).isNull();
  }

  @Test
  void theFirstEventIsAlwaysApplied() {
    IdentityState result =
        apply(IdentityState.initial(), IdentityStatus.SUSPENDED, T0, LOW).orElseThrow();

    assertThat(result).isEqualTo(new IdentityState(IdentityStatus.SUSPENDED, T0, LOW));
  }

  @Test
  void aLaterEventReplacesTheCurrentState() {
    IdentityState suspended = new IdentityState(IdentityStatus.SUSPENDED, T0, LOW);

    IdentityState reactivated =
        apply(suspended, IdentityStatus.ACTIVE, T0.plusMillis(1), LOW).orElseThrow();

    assertThat(reactivated.status()).isEqualTo(IdentityStatus.ACTIVE);
    assertThat(reactivated.eventAt()).isEqualTo(T0.plusMillis(1));
  }

  @Test
  void anEarlierEventArrivingLateChangesNothing() {
    // El caso real del spike: `reactivated` (…26.070) llegó antes que `suspended` (…25.970).
    IdentityState reactivated = new IdentityState(IdentityStatus.ACTIVE, T0.plusMillis(100), LOW);

    assertThat(apply(reactivated, IdentityStatus.SUSPENDED, T0, HIGH)).isEmpty();
  }

  @Test
  void theSameEventDeliveredAgainChangesNothing() {
    IdentityState suspended = new IdentityState(IdentityStatus.SUSPENDED, T0, LOW);

    assertThat(apply(suspended, IdentityStatus.SUSPENDED, T0, LOW)).isEmpty();
  }

  // ---- el empate: el identificador desempata, sin signo ----

  @Test
  void whenTwoEventsHaveTheSameInstantTheGreaterEventIdWins() {
    IdentityState low = new IdentityState(IdentityStatus.SUSPENDED, T0, LOW);

    IdentityState result = apply(low, IdentityStatus.ACTIVE, T0, HIGH).orElseThrow();

    assertThat(result.status()).isEqualTo(IdentityStatus.ACTIVE);
    assertThat(apply(result, IdentityStatus.SUSPENDED, T0, LOW)).isEmpty();
  }

  @Test
  void theTieBreakComparesTheUuidAsUnsignedBytesLikePostgresDoes() {
    // Con signo, 0xF000… sería MENOR que 0x0000…01 y el empate se resolvería al revés que en SQL.
    assertThat(HIGH.compareTo(LOW)).as("UUID.compareTo compara con signo").isNegative();
    assertThat(IdentityState.compareUnsigned(HIGH, LOW)).isPositive();
    assertThat(IdentityState.compareUnsigned(LOW, HIGH)).isNegative();
    assertThat(IdentityState.compareUnsigned(HIGH, HIGH)).isZero();
  }

  @Test
  void whenTheMostSignificantHalvesAreEqualTheLeastSignificantOnesDecide() {
    UUID a = new UUID(5L, 0x8000000000000000L);
    UUID b = new UUID(5L, 1L);

    assertThat(IdentityState.compareUnsigned(a, b)).isPositive();
    assertThat(IdentityState.compareUnsigned(b, a)).isNegative();
  }

  // ---- precisión de microsegundos, la de Postgres ----

  @Test
  void instantsThatOnlyDifferBelowAMicrosecondCountAsTheSameInstant() {
    IdentityState state = new IdentityState(IdentityStatus.SUSPENDED, T0.plusNanos(100_999), LOW);

    assertThat(state.eventAt()).as("se trunca a microsegundos").isEqualTo(T0.plusNanos(100_000));
    assertThat(apply(state, IdentityStatus.ACTIVE, T0.plusNanos(100_999), LOW))
        .as("mismo microsegundo y mismo id: no es posterior")
        .isEmpty();
    assertThat(apply(state, IdentityStatus.ACTIVE, T0.plusNanos(101_000), LOW))
        .as("un microsegundo después sí lo es")
        .isPresent();
  }

  // ---- DELETED: terminal y gana siempre ----

  @Test
  void deletedIsAppliedEvenWhenItsTimestampIsOlderThanTheCurrentState() {
    IdentityState reactivated = new IdentityState(IdentityStatus.ACTIVE, T0.plusSeconds(60), LOW);

    IdentityState deleted =
        apply(reactivated, IdentityStatus.DELETED, T0.minusSeconds(3600), LOW).orElseThrow();

    assertThat(deleted.status()).isEqualTo(IdentityStatus.DELETED);
  }

  @Test
  void onceDeletedNothingChangesItNotEvenALaterReactivation() {
    IdentityState deleted = new IdentityState(IdentityStatus.DELETED, T0, LOW);

    for (IdentityStatus status : IdentityStatus.values()) {
      assertThat(apply(deleted, status, T0.plusSeconds(3600), HIGH)).as(status.name()).isEmpty();
    }
  }

  // ---- invariantes ----

  @Test
  void theInstantAndTheIdOfTheLastEventGoTogether() {
    assertThatThrownBy(() -> new IdentityState(IdentityStatus.ACTIVE, T0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IdentityState(IdentityStatus.ACTIVE, null, LOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IdentityState(null, null, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void anEventWithoutItsFieldsIsRejected() {
    IdentityState state = IdentityState.initial();

    assertThatThrownBy(() -> state.accept(null, T0, LOW)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> state.accept(IdentityStatus.ACTIVE, null, LOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> state.accept(IdentityStatus.ACTIVE, T0, null))
        .isInstanceOf(NullPointerException.class);
  }

  // ---- la propiedad que importa: el orden de llegada no cambia el resultado ----

  private record Event(IdentityStatus status, Instant at, UUID id) {}

  private static IdentityState replay(List<Event> arrival) {
    IdentityState state = IdentityState.initial();
    for (Event event : arrival) {
      state = state.accept(event.status(), event.at(), event.id()).orElse(state);
    }
    return state;
  }

  private static List<Event> randomEvents(Random random, int count, boolean withDeletion) {
    List<Event> events = new ArrayList<>();
    IdentityStatus[] states = {IdentityStatus.ACTIVE, IdentityStatus.SUSPENDED};
    for (int i = 0; i < count; i++) {
      // Pocos instantes distintos a propósito: muchos empates exactos, el caso que rompía el orden.
      Instant at = T0.plusMillis(random.nextInt(4));
      UUID id = new UUID(random.nextLong(), random.nextLong());
      events.add(new Event(states[random.nextInt(2)], at, id));
    }
    if (withDeletion) {
      events.add(
          new Event(IdentityStatus.DELETED, T0.plusMillis(random.nextInt(4)), UUID.randomUUID()));
    }
    return events;
  }

  @Test
  void anyArrivalOrderOfTheSameEventsLeavesTheSameFinalState() {
    Random random = new Random(20261122L);
    for (int round = 0; round < 300; round++) {
      List<Event> events = randomEvents(random, 12, false);
      IdentityState expected = replay(events);

      for (int shuffle = 0; shuffle < 20; shuffle++) {
        List<Event> arrival = new ArrayList<>(events);
        Collections.shuffle(arrival, random);

        assertThat(replay(arrival)).as("ronda %d", round).isEqualTo(expected);
      }
    }
  }

  @Test
  void redeliveringEveryEventAnyNumberOfTimesChangesNothing() {
    Random random = new Random(7L);
    for (int round = 0; round < 200; round++) {
      List<Event> events = randomEvents(random, 10, false);
      List<Event> triplicated = new ArrayList<>();
      for (int copy = 0; copy < 3; copy++) {
        triplicated.addAll(events);
      }
      Collections.shuffle(triplicated, random);

      assertThat(replay(triplicated)).isEqualTo(replay(events));
    }
  }

  @Test
  void aDeletionAnywhereInTheSequenceAlwaysEndsDeletedWhateverComesAfter() {
    Random random = new Random(99L);
    for (int round = 0; round < 300; round++) {
      List<Event> events = randomEvents(random, 12, true);
      Collections.shuffle(events, random);

      assertThat(replay(events).status()).as("ronda %d", round).isEqualTo(IdentityStatus.DELETED);
    }
  }
}
