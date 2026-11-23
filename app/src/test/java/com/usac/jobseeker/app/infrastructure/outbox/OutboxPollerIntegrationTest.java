package com.usac.jobseeker.app.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La bandeja de salida contra Postgres real (ADR-0016): entrega al menos una vez, reintentos con
 * retroceso, FAILED al agotar intentos, `SKIP LOCKED` entre sondeadores y purga por retención.
 */
@SpringBootTest
@Import(InfrastructureTestConfiguration.class)
@WithoutOutboxScheduler
class OutboxPollerIntegrationTest {

  private static final String TYPE = "prueba.evento";
  @Autowired private OutboxConfiguration.OutboxScheduler scheduler;
  @Autowired private OutboxStore store;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactions;

  private final OutboxProperties properties =
      new OutboxProperties(
          Duration.ofSeconds(1), 10, 3, Duration.ofMillis(1), Duration.ofMillis(5));

  @Test
  void theRealSchedulerIsReplacedByAMockSoNoOtherPollerCanTakeTheRowsOfTheseTests() {
    // Guarda de @WithoutOutboxScheduler: si dejara de aplicarse, estas pruebas volverían a ser
    // intermitentes sin que nada lo avisara.
    assertThat(Mockito.mockingDetails(scheduler).isMock()).isTrue();
  }

  @BeforeEach
  void clean() {
    jdbc.update("delete from outbox_events");
  }

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private OutboxPoller poller(OutboxEventConsumer... consumers) {
    return new OutboxPoller(
        store, List.of(consumers), properties, new TransactionTemplate(transactions), meters);
  }

  private double count(String outcome) {
    var counter = meters.find("jobseeker_outbox_events_total").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }

  private static OutboxEventConsumer consumer(
      String type, java.util.function.Consumer<OutboxEvent> body) {
    return new OutboxEventConsumer() {
      @Override
      public String name() {
        return "prueba";
      }

      @Override
      public boolean handles(String eventType) {
        return type.equals(eventType);
      }

      @Override
      public void consume(OutboxEvent event) {
        body.accept(event);
      }
    };
  }

  private UUID publish(String type) {
    UUID id = UUID.randomUUID();
    store.publish(
        new OutboxEvent(id, "account", UUID.randomUUID(), 1, type, 1, "{\"k\":1}", "t-1"));
    return id;
  }

  private String status(UUID id) {
    return jdbc.queryForObject("select status from outbox_events where id = ?", String.class, id);
  }

  private int attempts(UUID id) {
    return jdbc.queryForObject(
        "select attempts from outbox_events where id = ?", Integer.class, id);
  }

  @Test
  void anEventIsDeliveredToItsConsumerWithItsContentAndMarkedProcessed() {
    UUID id = publish(TYPE);
    List<OutboxEvent> seen = new CopyOnWriteArrayList<>();

    int claimed = poller(consumer(TYPE, seen::add)).pollOnce();

    assertThat(claimed).isOne();
    assertThat(seen)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.id()).isEqualTo(id);
              assertThat(e.eventType()).isEqualTo(TYPE);
              assertThat(e.payload()).contains("\"k\"");
              assertThat(e.traceId()).isEqualTo("t-1");
            });
    assertThat(status(id)).isEqualTo("PROCESSED");
    assertThat(
            jdbc.queryForObject(
                "select processed_at is not null from outbox_events where id = ?",
                Boolean.class,
                id))
        .isTrue();
  }

  @Test
  void aProcessedEventIsNotDeliveredAgain() {
    publish(TYPE);
    List<OutboxEvent> seen = new CopyOnWriteArrayList<>();
    OutboxPoller poller = poller(consumer(TYPE, seen::add));

    poller.pollOnce();
    int second = poller.pollOnce();

    assertThat(second).isZero();
    assertThat(seen).hasSize(1);
  }

  @Test
  void aTypeNobodyHandlesIsMarkedProcessedBecauseRetryingWouldChangeNothing() {
    UUID id = publish("tipo.sin.consumidor");

    poller(consumer(TYPE, e -> {})).pollOnce();

    assertThat(status(id)).isEqualTo("PROCESSED");
  }

  @Test
  void aFailingConsumerLeavesTheEventPendingForARetryAndCountsTheAttempt() {
    UUID id = publish(TYPE);

    poller(
            consumer(
                TYPE,
                e -> {
                  throw new IllegalStateException("Redis caído");
                }))
        .pollOnce();

    assertThat(status(id)).isEqualTo("PENDING");
    assertThat(attempts(id)).isOne();
    assertThat(
            jdbc.queryForObject(
                "select available_at > created_at from outbox_events where id = ?",
                Boolean.class,
                id))
        .isTrue();
  }

  @Test
  void anEventThatFailsThenSucceedsIsEventuallyProcessedOnce() throws Exception {
    UUID id = publish(TYPE);
    int[] calls = {0};
    OutboxPoller poller =
        poller(
            consumer(
                TYPE,
                e -> {
                  if (++calls[0] == 1) {
                    throw new IllegalStateException("fallo transitorio");
                  }
                }));

    poller.pollOnce();
    jdbc.update("update outbox_events set available_at = now() where id = ?", id);
    poller.pollOnce();

    assertThat(status(id)).isEqualTo("PROCESSED");
    assertThat(calls[0]).isEqualTo(2);
  }

  @Test
  void afterTheMaximumAttemptsTheEventIsLeftFailedAndNeverRetriedAgain() {
    UUID id = publish(TYPE);
    int[] calls = {0};
    OutboxPoller poller =
        poller(
            consumer(
                TYPE,
                e -> {
                  calls[0]++;
                  throw new IllegalStateException("siempre");
                }));

    for (int i = 0; i < 6; i++) {
      jdbc.update("update outbox_events set available_at = now() where id = ?", id);
      poller.pollOnce();
    }

    assertThat(status(id)).isEqualTo("FAILED");
    assertThat(attempts(id)).isEqualTo(3);
    assertThat(calls[0]).as("se detuvo en el tope").isEqualTo(3);
  }

  @Test
  void oneFailingEventDoesNotBlockTheOthersInTheSameBatch() {
    UUID bad = publish(TYPE);
    UUID good = publish(TYPE);
    OutboxPoller poller =
        poller(
            consumer(
                TYPE,
                e -> {
                  if (e.id().equals(bad)) {
                    throw new IllegalStateException("solo este");
                  }
                }));

    poller.pollOnce();

    assertThat(status(good)).isEqualTo("PROCESSED");
    assertThat(status(bad)).isEqualTo("PENDING");
  }

  @Test
  void anEventNotYetAvailableIsNotClaimed() {
    UUID id = publish(TYPE);
    jdbc.update(
        "update outbox_events set available_at = now() + interval '1 hour' where id = ?", id);

    assertThat(poller(consumer(TYPE, e -> {})).pollOnce()).isZero();
    assertThat(status(id)).isEqualTo("PENDING");
  }

  @Test
  void theBatchSizeBoundsHowManyRowsAreClaimedPerPass() {
    for (int i = 0; i < 25; i++) {
      publish(TYPE);
    }

    assertThat(poller(consumer(TYPE, e -> {})).pollOnce()).isEqualTo(10);
  }

  @Test
  void twoPollersAtTheSameTimeNeverDeliverTheSameEventTwice() throws Exception {
    for (int i = 0; i < 40; i++) {
      publish(TYPE);
    }
    List<UUID> delivered = new CopyOnWriteArrayList<>();
    OutboxEventConsumer recording = consumer(TYPE, e -> delivered.add(e.id()));
    CountDownLatch together = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> runs = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        OutboxPoller poller = poller(recording);
        runs.add(
            pool.submit(
                () -> {
                  together.await();
                  for (int pass = 0; pass < 6; pass++) {
                    poller.pollOnce();
                  }
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

    assertThat(delivered).hasSize(40).doesNotHaveDuplicates();
    assertThat(store.countByStatus("PROCESSED")).isEqualTo(40);
  }

  @Test
  void onlyProcessedRowsOlderThanTheRetentionArePurged() {
    UUID old = publish(TYPE);
    UUID recent = publish(TYPE);
    UUID pending = publish("otro");
    jdbc.update(
        "update outbox_events set status='PROCESSED', processed_at = now() - interval '8 days' where id = ?",
        old);
    jdbc.update(
        "update outbox_events set status='PROCESSED', processed_at = now() - interval '1 day' where id = ?",
        recent);

    int purged = store.purgeProcessedBefore(Instant.now().minus(Duration.ofDays(7)));

    assertThat(purged).isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_events where id in (?, ?)",
                Long.class,
                recent,
                pending))
        .isEqualTo(2);
  }

  @Test
  void processedEventsRegistryDeduplicatesAndPurgesByRetention() {
    JdbcProcessedEvents registry =
        new JdbcProcessedEvents(org.springframework.jdbc.core.simple.JdbcClient.create(jdbc));
    UUID event = UUID.randomUUID();
    String consumer = "c-" + UUID.randomUUID();

    assertThat(registry.markProcessed(consumer, event)).isTrue();
    assertThat(registry.markProcessed(consumer, event)).isFalse();
    assertThat(registry.markProcessed("otro-" + consumer, event)).as("por consumidor").isTrue();

    jdbc.update(
        "update processed_events set processed_at = now() - interval '31 days' where consumer = ?",
        consumer);
    assertThat(registry.purgeOlderThan(Instant.now().minus(Duration.ofDays(30)))).isOne();
    assertThat(registry.markProcessed(consumer, event))
        .as("tras la purga vuelve a ser nuevo")
        .isTrue();
  }

  @Test
  void aConsumerIsOnlyCalledForTheTypesItHandles() {
    UUID id = publish("tipo.ajeno");
    OutboxEventConsumer neverForThisType =
        consumer(
            TYPE,
            e -> {
              throw new IllegalStateException("no debía llamarse");
            });

    poller(neverForThisType).pollOnce();

    assertThat(status(id)).isEqualTo("PROCESSED");
  }

  @Test
  void everyOutcomeIsCountedAsAMetric() {
    publish(TYPE);
    poller(consumer(TYPE, e -> {})).pollOnce();
    publish(TYPE);
    poller(
            consumer(
                TYPE,
                e -> {
                  throw new IllegalStateException("x");
                }))
        .pollOnce();
    UUID doomed = publish(TYPE);
    jdbc.update("update outbox_events set attempts = 2 where id = ?", doomed);
    jdbc.update("update outbox_events set available_at = now()");
    poller(
            consumer(
                TYPE,
                e -> {
                  throw new IllegalStateException("x");
                }))
        .pollOnce();

    assertThat(count("processed")).isOne();
    assertThat(count("failed")).isOne();
    assertThat(count("retried")).isGreaterThanOrEqualTo(1);
  }
}
