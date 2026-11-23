package com.usac.jobseeker.app.infrastructure.outbox;

import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Cableado de la bandeja de salida y de `processed_events` (ADR-0016). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxConfiguration.class);

  @Bean
  OutboxStore outboxStore(JdbcClient jdbc) {
    return new OutboxStore(jdbc);
  }

  @Bean
  JdbcProcessedEvents jdbcProcessedEvents(JdbcClient jdbc) {
    return new JdbcProcessedEvents(jdbc);
  }

  @Bean
  OutboxPoller outboxPoller(
      OutboxStore store,
      List<OutboxEventConsumer> consumers,
      OutboxProperties properties,
      PlatformTransactionManager transactions,
      MeterRegistry meters) {
    return new OutboxPoller(
        store, consumers, properties, new TransactionTemplate(transactions), meters);
  }

  @Bean
  OutboxScheduler outboxScheduler(
      OutboxPoller poller, OutboxStore store, JdbcProcessedEvents processedEvents) {
    return new OutboxScheduler(poller, store, processedEvents);
  }

  /** Gancho de planificación; un fallo de una pasada no debe detener las siguientes. */
  static class OutboxScheduler {
    // ADR-0016, decisión 8: la bandeja procesada se conserva 7 días y `processed_events` al menos
    // 30, más que cualquier ventana de reintento de un emisor externo.
    private static final java.time.Duration OUTBOX_RETENTION = java.time.Duration.ofDays(7);
    private static final java.time.Duration PROCESSED_EVENTS_RETENTION =
        java.time.Duration.ofDays(30);

    private final OutboxPoller poller;
    private final OutboxStore store;
    private final JdbcProcessedEvents processedEvents;

    OutboxScheduler(OutboxPoller poller, OutboxStore store, JdbcProcessedEvents processedEvents) {
      this.poller = poller;
      this.store = store;
      this.processedEvents = processedEvents;
    }

    // Una pasada fallida no debe cancelar las siguientes de la planificación.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Scheduled(cron = "0 15 3 * * *")
    void purge() {
      try {
        java.time.Instant now = java.time.Instant.now();
        int outbox = store.purgeProcessedBefore(now.minus(OUTBOX_RETENTION));
        int processed = processedEvents.purgeOlderThan(now.minus(PROCESSED_EVENTS_RETENTION));
        LOG.info(
            "event=outbox_purged outbox={} processedEvents={} Purga de retención de la bandeja",
            outbox,
            processed);
      } catch (RuntimeException e) {
        LOG.error("event=outbox_purge_failed Falló la purga de retención de la bandeja", e);
      }
    }

    // Una pasada fallida no debe cancelar las siguientes de la planificación.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Scheduled(fixedDelayString = "${jobseeker.outbox.poll-interval:1s}")
    void poll() {
      try {
        poller.pollOnce();
      } catch (RuntimeException e) {
        LOG.error("event=outbox_poll_failed Falló una pasada del sondeador de la bandeja", e);
      }
    }
  }
}
