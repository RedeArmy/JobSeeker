package com.usac.jobseeker.auth.application.usecase.handleclavariswebhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.auth.domain.model.IdentityState;
import com.usac.jobseeker.auth.domain.model.IdentityStatus;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Lo que el puerto del espejo de identidad promete, con independencia de la tecnología. La ejecutan
 * un almacén en memoria y el adaptador JDBC contra Postgres: si el SQL decide el orden de otra
 * manera que el dominio (por ejemplo comparando los UUID con signo), falla aquí.
 */
public abstract class IdentityStateContract {

  private static final Instant T0 = Instant.parse("2026-11-22T12:00:00.000Z");
  private static final UUID LOW = new UUID(0x0000000000000001L, 0x0000000000000001L);
  private static final UUID HIGH = new UUID(0xF000000000000000L, 0L);

  protected abstract IdentityStatePort port();

  /** Crea una cuenta nueva en el almacén y devuelve su identificador. */
  protected abstract AccountId newAccount();

  private record Event(IdentityStatus status, Instant at, UUID id) {}

  /**
   * Con varios DELETED el estado final es el mismo pero la marca del evento que lo fijó depende de
   * cuál llegó primero; como es terminal, esa marca no importa y no se compara.
   */
  private static IdentityState comparable(IdentityState state) {
    return state.status() == IdentityStatus.DELETED
        ? new IdentityState(IdentityStatus.DELETED, null, null)
        : state;
  }

  private IdentityState stateOf(AccountId account) {
    return port().findIdentityState(account).orElseThrow();
  }

  // ---- existencia y estado inicial ----

  @Test
  void anAccountThatWasCreatedExistsAndOneThatWasNotDoesNot() {
    assertThat(port().accountExists(newAccount())).isTrue();
    assertThat(port().accountExists(new AccountId(UUID.randomUUID()))).isFalse();
  }

  @Test
  void aNewAccountHasAnActiveIdentityAndNoEvent() {
    assertThat(stateOf(newAccount())).isEqualTo(IdentityState.initial());
  }

  @Test
  void anUnknownAccountHasNoStateAndAnEventForItChangesNothing() {
    AccountId unknown = new AccountId(UUID.randomUUID());

    assertThat(port().findIdentityState(unknown)).isEmpty();
    assertThat(port().applyIdentityEvent(unknown, IdentityStatus.SUSPENDED, T0, LOW)).isFalse();
    assertThat(port().accountExists(unknown)).isFalse();
  }

  // ---- aplicar eventos ----

  @Test
  void theFirstEventIsAppliedAndItsMarkAndIdAreKept() {
    AccountId account = newAccount();

    boolean changed = port().applyIdentityEvent(account, IdentityStatus.SUSPENDED, T0, LOW);

    assertThat(changed).isTrue();
    assertThat(stateOf(account)).isEqualTo(new IdentityState(IdentityStatus.SUSPENDED, T0, LOW));
  }

  @Test
  void anEarlierEventArrivingLateChangesNothingAndSaysSo() {
    AccountId account = newAccount();
    port().applyIdentityEvent(account, IdentityStatus.ACTIVE, T0.plusMillis(100), LOW);

    boolean changed = port().applyIdentityEvent(account, IdentityStatus.SUSPENDED, T0, HIGH);

    assertThat(changed).isFalse();
    assertThat(stateOf(account).status()).isEqualTo(IdentityStatus.ACTIVE);
  }

  @Test
  void theSameEventDeliveredAgainChangesNothing() {
    AccountId account = newAccount();
    port().applyIdentityEvent(account, IdentityStatus.SUSPENDED, T0, LOW);

    assertThat(port().applyIdentityEvent(account, IdentityStatus.SUSPENDED, T0, LOW)).isFalse();
  }

  @Test
  void aTieOnTheInstantIsDecidedByTheEventIdComparedAsUnsignedBytes() {
    // El UUID alto (0xF0…) es mayor sin signo y menor con signo: el SQL y el dominio deben
    // coincidir.
    AccountId low = newAccount();
    port().applyIdentityEvent(low, IdentityStatus.SUSPENDED, T0, LOW);
    assertThat(port().applyIdentityEvent(low, IdentityStatus.ACTIVE, T0, HIGH)).isTrue();
    assertThat(stateOf(low).status()).isEqualTo(IdentityStatus.ACTIVE);

    AccountId high = newAccount();
    port().applyIdentityEvent(high, IdentityStatus.SUSPENDED, T0, HIGH);
    assertThat(port().applyIdentityEvent(high, IdentityStatus.ACTIVE, T0, LOW)).isFalse();
    assertThat(stateOf(high).status()).isEqualTo(IdentityStatus.SUSPENDED);
  }

  @Test
  void deletedWinsEvenWithAnOlderMarkAndNothingChangesItAfterwards() {
    AccountId account = newAccount();
    port().applyIdentityEvent(account, IdentityStatus.ACTIVE, T0.plusSeconds(60), LOW);

    assertThat(
            port().applyIdentityEvent(account, IdentityStatus.DELETED, T0.minusSeconds(3600), LOW))
        .isTrue();
    for (IdentityStatus status : IdentityStatus.values()) {
      assertThat(port().applyIdentityEvent(account, status, T0.plusSeconds(7200), HIGH))
          .as(status.name())
          .isFalse();
    }
    assertThat(stateOf(account).status()).isEqualTo(IdentityStatus.DELETED);
  }

  @Test
  void aStateIsIndependentOfTheOtherAccounts() {
    AccountId first = newAccount();
    AccountId second = newAccount();

    port().applyIdentityEvent(first, IdentityStatus.DELETED, T0, LOW);

    assertThat(stateOf(first).status()).isEqualTo(IdentityStatus.DELETED);
    assertThat(stateOf(second)).isEqualTo(IdentityState.initial());
  }

  // ---- el resultado no depende del orden de llegada ni de la concurrencia ----

  private static List<Event> randomEvents(Random random, int count) {
    List<Event> events = new ArrayList<>();
    IdentityStatus[] states = IdentityStatus.values();
    for (int i = 0; i < count; i++) {
      // Pocos instantes distintos: muchos empates exactos, y UUID de todo el rango (incluidos los
      // que tienen el bit alto activo, donde el orden con y sin signo difiere).
      events.add(
          new Event(
              states[random.nextInt(states.length)],
              T0.plusMillis(random.nextInt(4)),
              new UUID(random.nextLong(), random.nextLong())));
    }
    return events;
  }

  private static IdentityState replayInTheDomain(List<Event> events) {
    IdentityState state = IdentityState.initial();
    for (Event event : events) {
      state = state.accept(event.status(), event.at(), event.id()).orElse(state);
    }
    return state;
  }

  @Test
  void anyArrivalOrderOfTheSameEventsLeavesWhatTheDomainRuleSays() {
    Random random = new Random(20261122L);
    for (int round = 0; round < 25; round++) {
      List<Event> events = randomEvents(random, 10);
      IdentityState expected = replayInTheDomain(events);

      for (int shuffle = 0; shuffle < 4; shuffle++) {
        List<Event> arrival = new ArrayList<>(events);
        Collections.shuffle(arrival, random);
        AccountId account = newAccount();
        for (Event event : arrival) {
          port().applyIdentityEvent(account, event.status(), event.at(), event.id());
        }

        assertThat(comparable(stateOf(account)))
            .as("ronda %d", round)
            .isEqualTo(comparable(expected));
      }
    }
  }

  @Test
  void simultaneousEventsOfTheSameAccountLeaveWhatTheDomainRuleSaysAndNothingFails()
      throws Exception {
    Random random = new Random(42L);
    List<Event> events = randomEvents(random, 200);
    IdentityState expected = replayInTheDomain(events);
    AccountId account = newAccount();
    int threads = 16;
    CountDownLatch startTogether = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (Event event : events) {
        results.add(
            pool.submit(
                () -> {
                  startTogether.await();
                  return port().applyIdentityEvent(account, event.status(), event.at(), event.id());
                }));
      }
      startTogether.countDown();
      for (Future<Boolean> result : results) {
        result.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(comparable(stateOf(account))).isEqualTo(comparable(expected));
  }
}
