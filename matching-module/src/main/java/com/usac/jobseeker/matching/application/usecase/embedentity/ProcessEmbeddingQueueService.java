package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.time.Clock;
import java.time.Duration;
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
  private final EmbedProfileUseCase embedProfile;
  private final EmbedJobUseCase embedJob;
  private final EmbeddingQueuePolicy policy;
  private final UseCaseMetricsPort metrics;
  private final EmbeddingFreshnessPort freshness;
  private final Clock clock;

  public ProcessEmbeddingQueueService(
      EmbeddingWorkQueuePort queue,
      EmbedProfileUseCase embedProfile,
      EmbedJobUseCase embedJob,
      EmbeddingQueuePolicy policy,
      UseCaseMetricsPort metrics,
      EmbeddingFreshnessPort freshness,
      Clock clock) {
    this.queue = Objects.requireNonNull(queue, "queue");
    this.embedProfile = Objects.requireNonNull(embedProfile, "embedProfile");
    this.embedJob = Objects.requireNonNull(embedJob, "embedJob");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.freshness = Objects.requireNonNull(freshness, "freshness");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public int execute() {
    List<EmbeddingWorkQueuePort.Job> jobs =
        queue.claim(policy.batchSize(), policy.lease(), policy.maxAttempts());
    jobs.forEach(this::run);
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
      case PROFILE -> embedProfile.execute(new EmbedProfileCommand(new AccountId(job.key())));
      case JOB -> embedJob.execute(new EmbedJobCommand(new JobPostingId(job.key())));
    }
  }

  private void retry(EmbeddingWorkQueuePort.Job job, String outcome) {
    queue.retryAt(job, clock.instant().plus(policy.backoff(job.attempts())));
    metrics.count(USE_CASE, outcome);
  }
}
