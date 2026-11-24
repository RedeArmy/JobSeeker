package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.EmbeddableText;
import com.usac.jobseeker.matching.domain.model.EntityEmbedding;
import com.usac.jobseeker.matching.domain.service.CanonicalProfileComposer;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). El orden de los pasos
 * importa:
 *
 * <ol>
 *   <li>Se registra la cuenta y se **sincroniza la descubribilidad antes** de llamar al modelo:
 *       ocultar un perfil debe notarse al instante y no esperar los segundos del embedding.
 *   <li>Se compara el *hash* y solo si cambió se llama al modelo, **sin transacción abierta**.
 *   <li>El vector se guarda con una escritura condicional a que la cuenta no esté anonimizada: si
 *       la anonimizaron mientras el modelo trabajaba, esta escritura no deja nada (BR-DATA-05).
 * </ol>
 *
 * <p>Ni el log ni la métrica llevan datos del perfil ni el identificador de la cuenta (BR-DATA-01).
 */
public class EmbedProfileService implements EmbedProfileUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(EmbedProfileService.class);
  private static final String USE_CASE = "embed_profile";

  private final CandidateSignalsReader reader;
  private final EmbeddingClientPort client;
  private final EmbeddingStorePort store;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;
  private final int maxCharacters;

  public EmbedProfileService(
      CandidateSignalsReader reader,
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
  public Result execute(EmbedProfileCommand command) {
    Optional<CandidateSignals> signals = reader.read(command.accountId());
    if (signals.isEmpty()) {
      return unavailable(command);
    }
    CandidateSignals current = signals.get();
    if (!store.registerProfile(command.accountId(), current.profileId())
        || !store.setProfileDiscoverable(current.profileId(), current.discoverable())) {
      return done(Result.NOTHING_TO_DO);
    }
    CanonicalProfileComposer.Composed composed =
        CanonicalProfileComposer.compose(current, maxCharacters);
    EmbeddableText text = composed.text();
    if (isUpToDate(current, text)) {
      return done(Result.UNCHANGED);
    }
    EmbeddingClientPort.Embedded embedded = client.embed(text);
    boolean saved =
        store.saveProfileEmbedding(
            current.profileId(),
            current.discoverable(),
            new EntityEmbedding(
                embedded.vector(),
                embedded.model(),
                text.contentHash(),
                composed.truncated() || embedded.truncated(),
                clock.instant()));
    return done(saved ? Result.EMBEDDED : Result.NOTHING_TO_DO);
  }

  // Un perfil que ya no se puede leer (capacidad apagada, anonimizado) deja de ser descubrible,
  // pero
  // su vector se conserva si no fue anonimizado: reactivar la capacidad lo recupera (TDD de cv,
  // D5).
  private Result unavailable(EmbedProfileCommand command) {
    Optional<com.usac.jobseeker.matching.domain.model.CandidateProfileId> known =
        store.findProfileIdByAccount(command.accountId());
    if (known.isEmpty()) {
      return done(Result.NOTHING_TO_DO);
    }
    store.setProfileDiscoverable(known.get(), false);
    return done(Result.HIDDEN);
  }

  private boolean isUpToDate(CandidateSignals current, EmbeddableText text) {
    return store
        .findProfileState(current.profileId())
        .flatMap(state -> state.embedding())
        .filter(
            meta ->
                meta.contentHash().equals(text.contentHash())
                    && meta.model().equals(client.model()))
        .isPresent();
  }

  private Result done(Result result) {
    metrics.count(USE_CASE, result.name().toLowerCase(java.util.Locale.ROOT));
    LOG.debug("event=profile_embedded outcome={} Cálculo del embedding de un perfil", result);
    return result;
  }
}
