package com.usac.jobseeker.app.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las dos garantías del despacho de la bandeja, contra Postgres real (ADR-0016):
 *
 * <ul>
 *   <li><b>TD-001:</b> un consumidor lento no retiene una transacción, una conexión del pool ni
 *       bloqueos de fila; una fila en vuelo no la reclama nadie más; si el proceso muere, el
 *       arrendamiento vence y se reclama de nuevo; un evento que mata el proceso en bucle acaba en
 *       FAILED.
 *   <li><b>TD-018:</b> nunca corren a la vez dos manejadores del mismo agregado, el de menor
 *       versión va primero y un reintento en espera no bloquea a los siguientes.
 * </ul>
 */
@SpringBootTest
@Import(InfrastructureTestConfiguration.class)
@WithoutOutboxScheduler
class OutboxDeliveryGuaranteesIntegrationTest {

  private static final String TYPE = "prueba.garantia";

  @Autowired private OutboxStore store;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactions;

  private final OutboxProperties properties =
      new OutboxProperties(
          Duration.ofSeconds(1),
          10,
          3,
          Duration.ofMillis(1),
          Duration.ofMillis(5),
          Duration.ofMinutes(5));

  @BeforeEach
  void clean() {
    jdbc.update("delete from outbox_events");
  }

  private OutboxPoller poller(OutboxEventConsumer consumer) {
    return new OutboxPoller(
        store,
        List.of(consumer),
        properties,
        new TransactionTemplate(transactions),
        new SimpleMeterRegistry());
  }

  private static OutboxEventConsumer consumer(Consumer<OutboxEvent> body) {
    return new OutboxEventConsumer() {
      @Override
      public String name() {
        return "garantias";
      }

      @Override
      public boolean handles(String eventType) {
        return TYPE.equals(eventType);
      }

      @Override
      public void consume(OutboxEvent event) {
        body.accept(event);
      }
    };
  }

  private UUID publish(UUID aggregate, long version) {
    UUID id = UUID.randomUUID();
    store.publish(new OutboxEvent(id, "perfil", aggregate, version, TYPE, 1, "{}", "t"));
    return id;
  }

  private String status(UUID id) {
    return jdbc.queryForObject("select status from outbox_events where id = ?", String.class, id);
  }

  private int attempts(UUID id) {
    return jdbc.queryForObject(
        "select attempts from outbox_events where id = ?", Integer.class, id);
  }

  private int idleInTransaction() {
    return jdbc.queryForObject(
        "select count(*) from pg_stat_activity where datname = current_database()"
            + " and state like 'idle in transaction%'",
        Integer.class);
  }

  // ---- TD-001: un consumidor lento no retiene recursos de la base de datos ----

  @Test
  void aSlowConsumerHoldsNoTransactionAndNoRowLockWhileItWorks() throws Exception {
    UUID id = publish(UUID.randomUUID(), 1);
    CountDownLatch working = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> pass =
          pool.submit(
              () ->
                  poller(
                          consumer(
                              e -> {
                                working.countDown();
                                try {
                                  release.await();
                                } catch (InterruptedException _) {
                                  Thread.currentThread().interrupt();
                                }
                              }))
                      .pollOnce());
      assertThat(working.await(10, TimeUnit.SECONDS)).isTrue();

      // Con el consumidor trabajando: ninguna transacción abierta y la fila no está bloqueada.
      assertThat(idleInTransaction()).as("sesiones con transacción abierta").isZero();
      boolean lockable =
          new TransactionTemplate(transactions)
              .execute(
                  s ->
                      !jdbc.queryForList(
                              "select id from outbox_events where id = ? for update nowait", id)
                          .isEmpty());
      assertThat(lockable).as("la fila se puede bloquear: nadie la tiene bloqueada").isTrue();
      release.countDown();
      assertThat(pass.get(10, TimeUnit.SECONDS)).isOne();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }

    assertThat(status(id)).isEqualTo("PROCESSED");
  }

  @Test
  void aRowInFlightIsNotClaimedByAnotherPollerAndIsDeliveredExactlyOnce() throws Exception {
    UUID id = publish(UUID.randomUUID(), 1);
    CountDownLatch working = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    List<UUID> delivered = new CopyOnWriteArrayList<>();
    OutboxEventConsumer slow =
        consumer(
            e -> {
              delivered.add(e.id());
              working.countDown();
              try {
                release.await();
              } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
              }
            });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> first = pool.submit(() -> poller(slow).pollOnce());
      assertThat(working.await(10, TimeUnit.SECONDS)).isTrue();

      int claimedByTheOther = poller(consumer(e -> delivered.add(e.id()))).pollOnce();

      assertThat(claimedByTheOther).as("la fila en vuelo no se reclama de nuevo").isZero();
      release.countDown();
      first.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }

    assertThat(delivered).containsExactly(id);
  }

  @Test
  void ifTheProcessDiesTheLeaseExpiresAndAnotherInstanceClaimsTheRowAgain() {
    UUID id = publish(UUID.randomUUID(), 1);
    // Una instancia reclama y muere sin marcar nada: queda arrendada.
    new TransactionTemplate(transactions)
        .executeWithoutResult(s -> store.claim(10, Duration.ofMinutes(5), 3));
    assertThat(poller(consumer(e -> {})).pollOnce()).as("arrendada: nadie la toma").isZero();

    jdbc.update(
        "update outbox_events set locked_until = now() - interval '1 second' where id = ?", id);
    List<UUID> delivered = new ArrayList<>();
    int reclaimed = poller(consumer(e -> delivered.add(e.id()))).pollOnce();

    assertThat(reclaimed).isOne();
    assertThat(delivered).containsExactly(id);
    assertThat(status(id)).isEqualTo("PROCESSED");
    assertThat(attempts(id)).as("los dos reclamos cuentan").isEqualTo(2);
  }

  @Test
  void anEventThatKeepsKillingTheProcessEndsFailedInsteadOfBeingClaimedForever() {
    UUID id = publish(UUID.randomUUID(), 1);
    TransactionTemplate tx = new TransactionTemplate(transactions);

    for (int crash = 0; crash < 3; crash++) {
      tx.executeWithoutResult(s -> store.claim(10, Duration.ofMinutes(5), 3));
      jdbc.update(
          "update outbox_events set locked_until = now() - interval '1 second' where id = ?", id);
    }
    int claimedAfterTheLimit = poller(consumer(e -> {})).pollOnce();

    assertThat(claimedAfterTheLimit).isZero();
    assertThat(status(id)).isEqualTo("FAILED");
    assertThat(attempts(id)).isEqualTo(3);
  }

  // ---- TD-018: orden por agregado ----

  @Test
  void theLowestVersionOfAnAggregateIsDeliveredFirstWhateverTheInsertionOrder() {
    UUID aggregate = UUID.randomUUID();
    UUID v3 = publish(aggregate, 3);
    UUID v1 = publish(aggregate, 1);
    UUID v2 = publish(aggregate, 2);
    List<UUID> delivered = new ArrayList<>();

    poller(consumer(e -> delivered.add(e.id()))).drain(10);

    assertThat(delivered).containsExactly(v1, v2, v3);
  }

  @Test
  void aPassTakesAtMostOneEventPerAggregate() {
    UUID aggregate = UUID.randomUUID();
    publish(aggregate, 1);
    publish(aggregate, 2);
    publish(UUID.randomUUID(), 1);

    assertThat(poller(consumer(e -> {})).pollOnce()).isEqualTo(2);
  }

  @Test
  void anEventWaitingForItsRetryDoesNotBlockTheNextVersionOfItsAggregate() {
    UUID aggregate = UUID.randomUUID();
    UUID v1 = publish(aggregate, 1);
    UUID v2 = publish(aggregate, 2);
    List<UUID> delivered = new ArrayList<>();
    OutboxEventConsumer failsV1 =
        consumer(
            e -> {
              if (e.id().equals(v1)) {
                throw new IllegalStateException("fallo transitorio");
              }
              delivered.add(e.id());
            });
    OutboxPoller poller = poller(failsV1);

    poller.pollOnce();
    // El retroceso del reintento de la v1 aún no vence: está esperando, no en vuelo.
    jdbc.update(
        "update outbox_events set available_at = now() + interval '1 hour' where id = ?", v1);
    poller.drain(10);

    assertThat(status(v1)).isEqualTo("PENDING");
    assertThat(delivered).as("la v2 no espera al reintento de la v1").containsExactly(v2);
  }

  @Test
  void neverTwoHandlersOfTheSameAggregateRunAtTheSameTimeEvenWithSeveralPollers() throws Exception {
    List<UUID> aggregates = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    for (UUID aggregate : aggregates) {
      for (long version = 1; version <= 8; version++) {
        publish(aggregate, version);
      }
    }
    Map<UUID, AtomicInteger> running = new ConcurrentHashMap<>();
    Map<UUID, List<Long>> order = new ConcurrentHashMap<>();
    AtomicInteger worstOverlap = new AtomicInteger();
    OutboxEventConsumer slow =
        consumer(
            e -> {
              int now =
                  running
                      .computeIfAbsent(e.aggregateId(), k -> new AtomicInteger())
                      .incrementAndGet();
              worstOverlap.accumulateAndGet(now, Math::max);
              order
                  .computeIfAbsent(e.aggregateId(), k -> new CopyOnWriteArrayList<>())
                  .add(e.aggregateVersion());
              // Retiene el manejador un instante para dar tiempo a que otro del mismo agregado
              // solape.
              LockSupport.parkNanos(Duration.ofMillis(15).toNanos());
              running.get(e.aggregateId()).decrementAndGet();
            });
    CountDownLatch together = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> runs = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        OutboxPoller poller = poller(slow);
        runs.add(
            pool.submit(
                () -> {
                  together.await();
                  poller.drain(40);
                  return null;
                }));
      }
      together.countDown();
      for (Future<?> run : runs) {
        run.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(worstOverlap.get()).as("manejadores simultáneos de un mismo agregado").isEqualTo(1);
    assertThat(store.countByStatus("PROCESSED")).isEqualTo(24);
    for (UUID aggregate : aggregates) {
      assertThat(order.get(aggregate))
          .as("orden de entrega")
          .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }
  }
}
