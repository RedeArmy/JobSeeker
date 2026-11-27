package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.recomputescoresforentity.RecomputeScoresCommand;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Cada trabajo termina de una
 * de cuatro formas, y ninguna tumba la pasada ni a los demás trabajos:
 *
 * <ul>
 *   <li>**Bien:** se completa (se borra, o se libera si llegó otra petición mientras corría).
 *   <li>**Modelo no disponible:** vuelve a la cola con retroceso. Se sirve el último score con
 *       aviso y la cola acumula (NFR-AVAIL-02); agotados los intentos queda en FAILED.
 *   <li>**Modelo rechaza** (fuera de contexto, dimensión distinta): FAILED sin gastar intentos.
 *   <li>**Fallo inesperado:** se trata como no disponible, para no perder el trabajo por un error
 *       pasajero.
 * </ul>
 *
 * <p>Ni el log ni la métrica llevan el identificador de la cuenta ni datos del perfil (BR-DATA-01).
 */
public class ProcessEmbeddingQueueService implements ProcessEmbeddingQueueUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessEmbeddingQueueService.class);
  private static final String USE_CASE = "process_embedding_queue";

  private final EmbeddingWorkQueuePort queue;
  private final EmbeddingJobHandlers handlers;
  private final EmbeddingQueuePolicy policy;
  private final UseCaseMetricsPort metrics;
  private final EmbeddingFreshnessPort freshness;
  private final Clock clock;

  public ProcessEmbeddingQueueService(
      EmbeddingWorkQueuePort queue,
      EmbeddingJobHandlers handlers,
      EmbeddingQueuePolicy policy,
      UseCaseMetricsPort metrics,
      EmbeddingFreshnessPort freshness,
      Clock clock) {
    this.queue = Objects.requireNonNull(queue, "queue");
    this.handlers = Objects.requireNonNull(handlers, "handlers");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.freshness = Objects.requireNonNull(freshness, "freshness");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public int execute() {
    Instant claimedAt = clock.instant();
    List<EmbeddingWorkQueuePort.Job> jobs =
        queue.claim(policy.batchSize(), policy.lease(), policy.maxAttempts());
    // El lote se arrienda entero pero se ejecuta en serie, y cada llamada al modelo puede tardar
    // hasta su tiempo de espera: pasado el 80 % del arrendamiento, lo que falta se devuelve sin
    // ejecutar, para que otro trabajador no lo tome mientras este aún lo tiene.
    Instant deadline = claimedAt.plus(policy.lease().multipliedBy(4).dividedBy(5));
    for (EmbeddingWorkQueuePort.Job job : jobs) {
      if (clock.instant().isBefore(deadline)) {
        run(job);
      } else {
        queue.release(job);
        metrics.count(USE_CASE, "released");
      }
    }
    return jobs.size();
  }

  // Cualquier fallo del cálculo se convierte en un reintento: un trabajo no debe perderse ni tumbar
  // la pasada por un error pasajero del modelo o de la red.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void run(EmbeddingWorkQueuePort.Job job) {
    try {
      calculate(job);
      queue.complete(job);
      metrics.count(USE_CASE, "done");
      // Solo un cálculo terminado mide la frescura: un fallo no la cumple ni la incumple todavía.
      freshness.observe(Duration.between(job.pendingSince(), clock.instant()));
    } catch (EmbeddingRejectedException rejected) {
      queue.fail(job);
      metrics.count(USE_CASE, "rejected");
      LOG.error(
          "event=embedding_rejected Una entidad no se pudo embeber y queda para revisión",
          rejected);
    } catch (EmbeddingUnavailableException _) {
      retry(job, "unavailable");
      LOG.warn("event=embedding_unavailable El modelo de embeddings no respondió; se reintenta");
    } catch (RuntimeException unexpected) {
      retry(job, "error");
      LOG.error("event=embedding_failed Fallo inesperado al embeber; se reintenta", unexpected);
    }
  }

  // Perfiles y vacantes comparten cola, trabajador y reintentos; solo cambia qué se calcula.
  private void calculate(EmbeddingWorkQueuePort.Job job) {
    switch (job.entity()) {
      case PROFILE -> handlers.profile().execute(new EmbedProfileCommand(new AccountId(job.key())));
      case JOB -> handlers.job().execute(new EmbedJobCommand(new JobPostingId(job.key())));
    }
    // Aunque el vector no haya cambiado (un cambio solo de datos estructurados no llama al modelo),
    // los puntajes guardados cambian: se recalculan siempre. Es aritmética, no una llamada externa,
    // y
    // si falla el trabajo se reintenta sin volver a llamar al modelo (el *hash* no cambió).
    handlers.recompute().execute(new RecomputeScoresCommand(job.entity(), job.key()));
  }

  private void retry(EmbeddingWorkQueuePort.Job job, String outcome) {
    queue.retryAt(job, clock.instant().plus(policy.backoff(job.attempts())));
    metrics.count(USE_CASE, outcome);
  }
}
