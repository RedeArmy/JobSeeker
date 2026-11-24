package com.usac.jobseeker.matching.infrastructure.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.usac.jobseeker.matching.infrastructure.MatchingPostgres;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Las métricas de frescura contra Postgres real (TD-029, ADR-0017 control C4): lo que miden los
 * medidores de la cola y el histograma de frescura, y que no llevan datos del perfil.
 */
class EmbeddingQueueMetricsTest {

  private final JdbcClient jdbc = JdbcClient.create(MatchingPostgres.dataSource());
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private EmbeddingQueueMetrics metrics;

  @BeforeEach
  void clean() {
    jdbc.sql("delete from matching_work_queue").update();
    metrics = new EmbeddingQueueMetrics(jdbc);
    metrics.bindTo(meters);
  }

  private void job(String status, double secondsFromNow) {
    jdbc.sql(
            "insert into matching_work_queue (id, entity_type, entity_key, not_before, status)"
                + " values (:id, 'PROFILE', :key, now() + make_interval(secs => :seconds), :status)")
        .param("id", UUID.randomUUID())
        .param("key", UUID.randomUUID())
        .param("seconds", secondsFromNow)
        .param("status", status)
        .update();
  }

  private double gauge(String name, String... tag) {
    var search = meters.find(name);
    if (tag.length == 2) {
      search = search.tag(tag[0], tag[1]);
    }
    return search.gauge().value();
  }

  @Test
  void anEmptyQueueReadsAsZeroEverywhere() {
    assertThat(gauge("jobseeker_matching_queue_oldest_age_seconds")).isZero();
    assertThat(gauge("jobseeker_matching_queue_depth", "status", "pending")).isZero();
    assertThat(gauge("jobseeker_matching_queue_depth", "status", "failed")).isZero();
  }

  @Test
  void theDepthCountsPendingAndFailedSeparately() {
    job("PENDING", -1);
    job("PENDING", 3600);
    job("FAILED", -1);

    assertThat(gauge("jobseeker_matching_queue_depth", "status", "pending")).isEqualTo(2);
    assertThat(gauge("jobseeker_matching_queue_depth", "status", "failed")).isOne();
  }

  // La ventana de agrupación es espera deliberada: un trabajo aún dentro de ella no es retraso.
  @Test
  void theOldestAgeIgnoresJobsStillInsideTheirWindowAndFailedOnes() {
    job("PENDING", 3600);
    job("FAILED", -7200);

    assertThat(gauge("jobseeker_matching_queue_oldest_age_seconds")).isZero();
  }

  @Test
  void theOldestAgeIsHowLongTheOldestReadyJobHasBeenWaiting() {
    job("PENDING", -300);
    job("PENDING", -10);

    assertThat(gauge("jobseeker_matching_queue_oldest_age_seconds")).isBetween(299.0, 330.0);
  }

  @Test
  void freshnessIsRecordedInAHistogramThatHasTheTargetAsABucket() {
    metrics.observe(Duration.ofSeconds(8));
    metrics.observe(Duration.ofSeconds(100));
    metrics.observe(Duration.ofSeconds(400));

    var timer = meters.find("jobseeker_matching_freshness_seconds").timer();
    assertThat(timer.count()).isEqualTo(3);
    var buckets = timer.takeSnapshot().histogramCounts();
    assertThat(buckets)
        .anySatisfy(
            b -> assertThat(b.bucket(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(120.0));
  }

  @Test
  void aNegativeDurationFromAClockStepIsRecordedAsZeroNotAsNonsense() {
    metrics.observe(Duration.ofSeconds(-5));

    assertThat(
            meters
                .find("jobseeker_matching_freshness_seconds")
                .timer()
                .totalTime(java.util.concurrent.TimeUnit.SECONDS))
        .isZero();
  }

  @Test
  void recordingBeforeBindingIsIgnoredInsteadOfFailing() {
    var unbound = new EmbeddingQueueMetrics(jdbc);

    assertThatCode(() -> unbound.observe(Duration.ofSeconds(1))).doesNotThrowAnyException();
  }

  @Test
  void theMeterNamesCarryNoPersonalDataOrAccountLabels() {
    metrics.observe(Duration.ofSeconds(1));

    assertThat(meters.getMeters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags())
                    .extracting(io.micrometer.core.instrument.Tag::getKey)
                    .isSubsetOf("status", "le"));
  }

  @Test
  void theQueueIsReadOncePerWindowNotOncePerMeter() {
    AtomicInteger reads = new AtomicInteger();
    var cached =
        new EmbeddingQueueMetrics.CachedValue<>(Duration.ofMinutes(1), reads::incrementAndGet);

    cached.get();
    cached.get();
    cached.get();

    assertThat(reads).hasValue(1);
  }
}
