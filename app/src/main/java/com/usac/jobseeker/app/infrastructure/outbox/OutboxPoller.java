package com.usac.jobseeker.app.infrastructure.outbox;

import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Despacha la bandeja a los consumidores (ADR-0016, decisiones 2, 4 y 5). La entrega es **al menos
 * una vez**: si un consumidor falla se reintenta con retroceso exponencial y, agotados los
 * intentos, la fila queda en FAILED. Una fila cuyo tipo nadie atiende se da por procesada, porque
 * reintentarla no cambiaría nada.
 *
 * <p>Cada pasada reclama con `SKIP LOCKED` dentro de una transacción; si el proceso muere a mitad,
 * las filas vuelven a estar pendientes y los consumidores, idempotentes, las repiten sin daño.
 */
public class OutboxPoller {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxPoller.class);
  private static final String LEASE_LOST = "lease_lost";

  private final OutboxStore store;
  private final List<OutboxEventConsumer> consumers;
  private final OutboxProperties properties;
  private final TransactionTemplate transaction;
  private final MeterRegistry meters;
  private final LongSupplier nanoClock;

  public OutboxPoller(
      OutboxStore store,
      List<OutboxEventConsumer> consumers,
      OutboxProperties properties,
      TransactionTemplate transaction,
      MeterRegistry meters) {
    this(store, consumers, properties, transaction, meters, System::nanoTime);
  }

  /** El reloj monotónico es inyectable para probar el vencimiento del arrendamiento sin esperar. */
  OutboxPoller(
      OutboxStore store,
      List<OutboxEventConsumer> consumers,
      OutboxProperties properties,
      TransactionTemplate transaction,
      MeterRegistry meters,
      LongSupplier nanoClock) {
    this.store = store;
    this.consumers = List.copyOf(consumers);
    this.properties = properties;
    this.transaction = transaction;
    this.meters = meters;
    this.nanoClock = nanoClock;
  }

  /**
   * Una pasada; devuelve cuántas filas reclamó. Solo el reclamo es transaccional y corto: el
   * despacho a los consumidores (que pueden tardar, como una llamada a Ollama) ocurre **sin
   * transacción abierta**, así que no retiene una conexión del pool ni bloqueos de fila (TD-001).
   */
  public int pollOnce() {
    // `execute` solo devuelve nulo si el callback lo devuelve, y aquí nunca lo hace.
    long claimedAt = nanoClock.getAsLong();
    List<OutboxStore.Claimed> batch =
        transaction.execute(
            status ->
                store.claim(properties.batchSize(), properties.lease(), properties.maxAttempts()));
    // Todo el lote se arrienda a la vez pero se despacha en serie: si los primeros tardan, los
    // últimos llegarían con el arrendamiento vencido y otra pasada podría tomarlos al mismo tiempo,
    // rompiendo el orden por agregado (ADR-0016, decisión 4). Pasado el margen, se devuelven sin
    // despachar.
    long deadline = claimedAt + safeWindow(properties.lease()).toNanos();
    List<OutboxStore.Claimed> dispatched = new ArrayList<>();
    for (OutboxStore.Claimed claimed : batch) {
      if (nanoClock.getAsLong() - deadline >= 0) {
        if (store.release(claimed.event().id(), claimed.attempts())) {
          count("released");
        }
      } else if (dispatch(claimed)) {
        dispatched.add(claimed);
      }
    }
    // TD-011: los despachados se marcan juntos, en una sentencia, al final de la pasada. Siguen
    // arrendados hasta entonces, así que nadie más los toma; si el proceso muere antes de marcarlos
    // se vuelven a entregar, que los consumidores idempotentes toleran (ADR-0016, decisión 3).
    int marked = store.markProcessed(dispatched);
    count("processed", marked);
    count(LEASE_LOST, dispatched.size() - marked);
    return batch.size();
  }

  /**
   * Repite pasadas mientras haya trabajo, con un tope. Como de cada agregado se despacha un evento
   * por pasada (orden por agregado), una ráfaga de cambios de un mismo perfil tardaría una pasada
   * por evento si cada una esperara el siguiente sondeo; así se vacía enseguida. El tope evita que
   * una pasada del planificador monopolice el hilo.
   *
   * @return cuántas filas se reclamaron en total
   */
  public int drain(int maxPasses) {
    int total = 0;
    for (int pass = 0; pass < maxPasses; pass++) {
      int claimed = pollOnce();
      total += claimed;
      if (claimed == 0) {
        break;
      }
    }
    return total;
  }

  /** Se deja un 20 % del arrendamiento como margen para el despacho más lento de un evento. */
  static Duration safeWindow(Duration lease) {
    return lease.multipliedBy(4).dividedBy(5);
  }

  // Cualquier fallo de un consumidor se reintenta con retroceso; no debe tumbar el lote entero.
  // Devuelve si todos los consumidores lo procesaron: entonces se marca con el resto de la pasada.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private boolean dispatch(OutboxStore.Claimed claimed) {
    OutboxEvent event = claimed.event();
    try {
      for (OutboxEventConsumer consumer : consumers) {
        if (consumer.handles(event.eventType())) {
          consumer.consume(event);
        }
      }
      return true;
    } catch (RuntimeException e) {
      // El intento ya se contó al reclamar.
      int attempt = claimed.attempts();
      if (attempt >= properties.maxAttempts()) {
        count(store.markFailed(event.id(), attempt) ? "failed" : LEASE_LOST);
        LOG.error(
            "event=outbox_failed eventType={} attempts={} Evento de la bandeja agotó sus intentos",
            event.eventType(),
            attempt,
            e);
      } else {
        boolean owned =
            store.markRetry(
                event.id(),
                attempt,
                properties.backoff(attempt, ThreadLocalRandom.current().nextDouble()));
        count(owned ? "retried" : LEASE_LOST);
        LOG.warn(
            "event=outbox_retry eventType={} attempt={} Evento de la bandeja se reintentará",
            event.eventType(),
            attempt,
            e);
      }
      return false;
    }
  }

  private void count(String outcome) {
    count(outcome, 1);
  }

  private void count(String outcome, int times) {
    if (times > 0) {
      meters.counter("jobseeker_outbox_events_total", "outcome", outcome).increment(times);
    }
  }
}
