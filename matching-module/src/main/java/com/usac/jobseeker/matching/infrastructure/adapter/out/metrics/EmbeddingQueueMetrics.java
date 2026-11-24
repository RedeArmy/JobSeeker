package com.usac.jobseeker.matching.infrastructure.adapter.out.metrics;

import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingFreshnessPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Las tres métricas de frescura de `matching` (TDD §3, ADR-0017 control C4), que son lo que permite
 * vigilar y demostrar el objetivo de p95 ≤ 120 s desde la edición hasta el vector:
 *
 * <ul>
 *   <li>`jobseeker_matching_freshness_seconds`: histograma del tiempo desde que había una edición
 *       sin atender hasta que su cálculo terminó. Sus cubos incluyen 120 s, el objetivo.
 *   <li>`jobseeker_matching_queue_oldest_age_seconds`: cuánto lleva esperando el trabajo **listo**
 *       más viejo, sin contar la ventana de agrupación (que es espera deliberada). Una cola
 *       atascada sube sin parar; una sana se mantiene en segundos.
 *   <li>`jobseeker_matching_queue_depth{status}`: trabajos pendientes y fallidos. Los fallidos son
 *       trabajo que alguien debe revisar.
 * </ul>
 *
 * <p>Los medidores consultan la base de datos al ser leídos, así que se guarda el último valor unos
 * segundos: un raspado de Prometheus no debe traducirse en una consulta por medidor. Sin datos
 * personales: solo conteos y tiempos.
 */
public class EmbeddingQueueMetrics implements EmbeddingFreshnessPort, MeterBinder {

  static final String FRESHNESS = "jobseeker_matching_freshness_seconds";
  static final String OLDEST_AGE = "jobseeker_matching_queue_oldest_age_seconds";
  static final String DEPTH = "jobseeker_matching_queue_depth";
  private static final Duration CACHE = Duration.ofSeconds(5);

  private final JdbcClient jdbc;
  private final java.util.concurrent.atomic.AtomicReference<MeterRegistry> registry =
      new java.util.concurrent.atomic.AtomicReference<>();
  private final CachedValue<double[]> snapshot = new CachedValue<>(CACHE, this::readQueue);

  public EmbeddingQueueMetrics(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void bindTo(MeterRegistry meters) {
    registry.set(meters);
    Gauge.builder(OLDEST_AGE, () -> snapshot.get()[0])
        .description(
            "Espera del trabajo de embedding listo más viejo, sin la ventana de agrupación")
        .baseUnit("seconds")
        .register(meters);
    Gauge.builder(DEPTH, () -> snapshot.get()[1])
        .tag("status", "pending")
        .description("Trabajos de embedding pendientes")
        .register(meters);
    Gauge.builder(DEPTH, () -> snapshot.get()[2])
        .tag("status", "failed")
        .description("Trabajos de embedding fallidos, para revisión")
        .register(meters);
  }

  @Override
  public void observe(Duration sinceEdit) {
    MeterRegistry meters = registry.get();
    if (meters == null) {
      return;
    }
    Timer.builder(FRESHNESS)
        .description("Tiempo desde una edición sin atender hasta que su embedding queda calculado")
        .serviceLevelObjectives(
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            Duration.ofSeconds(120),
            Duration.ofSeconds(300))
        .register(meters)
        .record(sinceEdit.isNegative() ? Duration.ZERO : sinceEdit);
  }

  /** `[espera del listo más viejo en segundos, pendientes, fallidos]`. */
  private double[] readQueue() {
    return jdbc.sql(
            "select coalesce(extract(epoch from clock_timestamp() - min(not_before)"
                + " filter (where status = 'PENDING' and not_before <= clock_timestamp())), 0),"
                + " count(*) filter (where status = 'PENDING'),"
                + " count(*) filter (where status = 'FAILED') from matching_work_queue")
        .query((rows, n) -> new double[] {rows.getDouble(1), rows.getDouble(2), rows.getDouble(3)})
        .single();
  }

  /** Un valor que se vuelve a calcular como mucho una vez por ventana. */
  static final class CachedValue<T> {
    private final long windowNanos;
    private final Supplier<T> source;
    private T value;
    private long readAt;
    private boolean loaded;

    CachedValue(Duration window, Supplier<T> source) {
      this.windowNanos = window.toNanos();
      this.source = source;
    }

    synchronized T get() {
      long now = System.nanoTime();
      if (!loaded || now - readAt >= windowNanos) {
        value = source.get();
        readAt = now;
        loaded = true;
      }
      return value;
    }
  }
}
