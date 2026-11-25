package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.matching.domain.model.EmbeddableText;
import com.usac.jobseeker.matching.domain.model.EntityEmbedding;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import com.usac.jobseeker.matching.domain.service.CanonicalJobComposer;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Igual que el del perfil, el
 * orden importa:
 *
 * <ol>
 *   <li>Se **sincronizan las copias antes de llamar al modelo**: que una vacante se cierre, se
 *       archive o pase a `REVIEWING` debe notarse en los sugeridos al instante, no esperar los
 *       segundos del embedding.
 *   <li>Solo una vacante **publicada** se embebe. Una cerrada o archivada conserva el vector que
 *       tenía, para que los puntajes ya calculados se puedan explicar, pero deja de ser candidata.
 *   <li>Se compara el *hash* y solo si cambió se llama al modelo, **sin transacción abierta**.
 * </ol>
 *
 * <p>Ni el log ni la métrica llevan el identificador de la vacante ni su contenido (BR-DATA-01).
 */
public class EmbedJobService implements EmbedJobUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(EmbedJobService.class);
  private static final String USE_CASE = "embed_job";

  private final JobSignalsReader reader;
  private final EmbeddingClientPort client;
  private final EmbeddingStorePort store;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;
  private final int maxCharacters;

  public EmbedJobService(
      JobSignalsReader reader,
      EmbeddingClientPort client,
      EmbeddingStorePort store,
      UseCaseMetricsPort metrics,
      Clock clock,
      int maxCharacters) {
    this.reader = Objects.requireNonNull(reader, "reader");
    this.client = Objects.requireNonNull(client, "client");
    this.store = Objects.requireNonNull(store, "store");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (maxCharacters <= 0) {
      throw new IllegalArgumentException("el presupuesto de caracteres debe ser positivo");
    }
    this.maxCharacters = maxCharacters;
  }

  @Override
  public Result execute(EmbedJobCommand command) {
    Optional<JobSignals> signals = reader.read(command.jobId());
    if (signals.isEmpty()) {
      return unavailable(command);
    }
    JobSignals current = signals.get();
    store.setJobCopies(current.jobId(), current.published(), current.reviewing());
    if (!current.published()) {
      return done(Result.NOT_PUBLISHED);
    }
    CanonicalJobComposer.Composed composed = CanonicalJobComposer.compose(current, maxCharacters);
    EmbeddableText text = composed.text();
    if (isUpToDate(current, text)) {
      return done(Result.UNCHANGED);
    }
    EmbeddingClientPort.Embedded embedded = client.embed(text);
    store.saveJobEmbedding(
        current.jobId(),
        current.published(),
        new EntityEmbedding(
            embedded.vector(),
            embedded.model(),
            text.contentHash(),
            composed.truncated() || embedded.truncated(),
            clock.instant()));
    return done(Result.EMBEDDED);
  }

  // Una vacante que ya no se puede leer (eliminada, o un borrador que se descartó) deja de ser
  // candidata, pero su vector se conserva: la fila se mantiene por integridad con los puntajes
  // (BR-JOB-09). Si `matching` no sabía nada de ella, no se crea una fila vacía.
  private Result unavailable(EmbedJobCommand command) {
    Optional<JobEmbeddingState> known = store.findJobState(command.jobId());
    if (known.isEmpty()) {
      return done(Result.NOTHING_TO_DO);
    }
    store.setJobCopies(command.jobId(), false, known.get().reviewing());
    return done(Result.NOT_PUBLISHED);
  }

  private boolean isUpToDate(JobSignals current, EmbeddableText text) {
    return store
        .findJobState(current.jobId())
        .flatMap(JobEmbeddingState::embedding)
        .filter(
            meta ->
                meta.contentHash().equals(text.contentHash())
                    && meta.model().equals(client.model()))
        .isPresent();
  }

  private Result done(Result result) {
    metrics.count(USE_CASE, result.name().toLowerCase(Locale.ROOT));
    LOG.debug("event=job_embedded outcome={} Cálculo del embedding de una vacante", result);
    return result;
  }
}
