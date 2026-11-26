package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.SuggestionsDeniedException;
import com.usac.jobseeker.common.domain.error.SuggestionsDeniedException.Reason;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import com.usac.jobseeker.matching.domain.model.Availability;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.CompatibilityScore;
import com.usac.jobseeker.matching.domain.model.EntityEmbedding;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import com.usac.jobseeker.matching.domain.model.StoredScore;
import com.usac.jobseeker.matching.domain.service.CandidateCards;
import com.usac.jobseeker.matching.domain.service.CompatibilityAggregator;
import com.usac.jobseeker.matching.domain.service.SuggestionRanking;
import com.usac.jobseeker.matching.domain.service.ViewQuota;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Es la defensa central
 * contra el raspado de datos de candidatos (amenaza T-N1, REQ-R-10 y REQ-R-11), así que **el orden
 * de los controles es parte del diseño**:
 *
 * <ol>
 *   <li>Capacidad y verificación primero, antes de tocar la vacante: un reclutador sin verificar o
 *       suspendido no aprende nada de ella, ni siquiera si existe (BR-ACCT-11).
 *   <li>Propiedad y estado de la vacante: la de otro no existe para quien pregunta (BR-VIS-03) y
 *       una no publicada no genera sugeridos (BR-JOB-06). En `REVIEWING` deja de generar entradas
 *       nuevas (BR-MATCH-08).
 *   <li>La cuota al final y bajo un candado por reclutador, **en la misma transacción** que el
 *       registro de lo que se muestra: así dos pedidos simultáneos no la superan juntos, y un
 *       pedido negado no deja nada registrado.
 * </ol>
 *
 * <p>Solo se usan perfiles **descubribles** (BR-VIS-04, lo garantiza la recuperación). La respuesta
 * no lleva similitud ni puntaje: el coseno bruto no es un porcentaje (ADR-0017). Ni el log ni la
 * métrica llevan identificadores de la cuenta, de la vacante ni de los candidatos (BR-DATA-01).
 */
public class SuggestCandidatesForJobService implements SuggestCandidatesForJobUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(SuggestCandidatesForJobService.class);
  private static final String USE_CASE = "suggest_candidates_for_job";

  private final RecruiterTrustPort trust;
  private final SuggestionsSources sources;
  private final ProfileViewLogPort views;
  private final SuggestionsPolicy policy;
  private final ScoringModel scoring;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;

  public SuggestCandidatesForJobService(
      RecruiterTrustPort trust,
      SuggestionsSources sources,
      ProfileViewLogPort views,
      SuggestionsPolicy policy,
      ScoringModel scoring,
      UseCaseMetricsPort metrics,
      Clock clock) {
    this.trust = Objects.requireNonNull(trust, "trust");
    this.sources = Objects.requireNonNull(sources, "sources");
    this.views = Objects.requireNonNull(views, "views");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.scoring = Objects.requireNonNull(scoring, "scoring");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional
  public SuggestionsResult execute(SuggestCandidatesForJobQuery query) {
    requireVerifiedRecruiter(query.recruiter());
    OwnedJobPort.OwnedJob job =
        sources
            .ownedJobs()
            .find(query.recruiter(), query.job())
            .orElseThrow(() -> failed("not_found", new NotFoundException()));
    if (!job.published()) {
      throw failed("not_published", new InvalidStateException());
    }
    Instant now = clock.instant();
    Instant since = now.minus(policy.window());
    if (job.reviewing()) {
      return unavailable(SuggestionsResult.Status.PAUSED, query.recruiter(), since);
    }
    Optional<EntityEmbedding> embedding = sources.embeddings().findJobEmbedding(query.job());
    if (embedding.isEmpty()) {
      return unavailable(SuggestionsResult.Status.PENDING_EMBEDDING, query.recruiter(), since);
    }
    JobSignals jobSignals =
        sources
            .jobs()
            .read(query.job())
            .orElseThrow(() -> failed("not_published", new InvalidStateException()));
    LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
    List<NearestProfile> recovered =
        sources
            .retrieval()
            .nearestDiscoverable(
                embedding.get().vector(), embedding.get().model(), policy.retrievalCap());
    Map<CandidateProfileId, SuggestedCandidate> scored = new HashMap<>();
    List<SuggestionRanking.Entry> entries = new ArrayList<>();
    int incomplete =
        scoreRecovered(recovered, jobSignals, query.recruiter(), today, scored, entries);
    entries.sort(SuggestionRanking.order(policy.tieTolerance()));
    List<CandidateProfileId> ranked =
        entries.stream()
            .limit(Math.min(query.size(), policy.maxPageSize()))
            .map(entry -> new CandidateProfileId(entry.candidate()))
            .toList();

    views.lock(query.recruiter());
    Set<CandidateProfileId> alreadyViewed = views.viewedSince(query.recruiter(), since);
    ViewQuota.Allowance allowance = ViewQuota.apply(ranked, alreadyViewed, policy.viewQuota());
    if (allowance.exhausted()) {
      throw denied(Reason.QUOTA_EXCEEDED, "quota_exceeded");
    }
    views.append(query.recruiter(), query.job(), allowance.allowed(), now);
    List<SuggestedCandidate> shown = allowance.allowed().stream().map(scored::get).toList();
    // Lo que un reclutador vio es un par «con motivo» (ADR-0017, decisión 9): se guarda su puntaje
    // vigente, y los recálculos lo mantienen al día. Una sola sentencia para todos los mostrados.
    sources
        .scores()
        .upsertCurrent(
            shown.stream()
                .map(
                    c ->
                        new StoredScore(
                            c.profile(), query.job(), c.score(), ScoreReason.SUGGESTED, now))
                .toList());
    return served(
        SuggestionsResult.Status.OK, shown, allowance.remaining(), recovered.size(), incomplete);
  }

  /**
   * Etapa 2 del pipeline (ADR-0017): puntúa a los recuperados con aritmética sobre lo que ya hay,
   * sin llamar a ningún modelo. Se descarta a quien ya no es descubrible según su perfil vivo (la
   * copia de `discoverable` del índice puede ir un evento por detrás, BR-DATA-03) y a quien declaró
   * menos de la completitud mínima: omitir datos no debe poder subir el puntaje de nadie (R1).
   * Devuelve cuántos se descartaron por incompletos.
   */
  private int scoreRecovered(
      List<NearestProfile> recovered,
      JobSignals jobSignals,
      AccountId recruiter,
      LocalDate today,
      Map<CandidateProfileId, SuggestedCandidate> scored,
      List<SuggestionRanking.Entry> entries) {
    // Todas las señales con pocas consultas, no una tanda por candidato (TD-039).
    Map<AccountId, CandidateSignals> signalsOf =
        sources.candidates().readAll(recovered.stream().map(NearestProfile::account).toList());
    int incomplete = 0;
    List<ScoredPair> complete = new ArrayList<>();
    for (NearestProfile nearest : recovered) {
      Scoring result = scoreOne(nearest, signalsOf.get(nearest.account()), jobSignals, today);
      switch (result.status()) {
        case INCOMPLETE -> incomplete++;
        case SCORED -> complete.add(result.pair());
        case SKIPPED -> {
          // Ya no es descubrible o no tiene perfil legible: no entra y no cuenta como incompleto.
        }
      }
    }
    // BR-MATCH-09: la disponibilidad es la que ve ESTE reclutador (UNSET si fue excluido); se
    // pregunta
    // una vez por todos los que pasaron el filtro de completitud.
    Map<AccountId, Availability> availabilityOf =
        sources
            .availability()
            .visibleToAll(
                recruiter, complete.stream().map(pair -> pair.nearest().account()).toList());
    for (ScoredPair pair : complete) {
      Availability availability =
          availabilityOf.getOrDefault(pair.nearest().account(), Availability.UNSET);
      SuggestedCandidate candidate =
          new SuggestedCandidate(
              pair.nearest().candidate(),
              pair.score(),
              availability,
              CandidateCards.of(pair.signals(), today));
      scored.put(pair.nearest().candidate(), candidate);
      entries.add(
          new SuggestionRanking.Entry(
              pair.nearest().candidate().value(), pair.score().overall(), availability));
    }
    return incomplete;
  }

  /** Cómo quedó un recuperado en la etapa 2. */
  private enum Status {
    SKIPPED,
    INCOMPLETE,
    SCORED
  }

  /** Un recuperado ya puntuado, a la espera de la disponibilidad que ve el reclutador. */
  private record ScoredPair(
      NearestProfile nearest, CandidateSignals signals, CompatibilityScore score) {}

  private record Scoring(Status status, ScoredPair pair) {}

  // Puntúa a un recuperado con aritmética, sin llamar a ningún modelo; `signals` ya viene leído.
  private Scoring scoreOne(
      NearestProfile nearest, CandidateSignals signals, JobSignals jobSignals, LocalDate today) {
    if (signals == null || !signals.discoverable()) {
      return new Scoring(Status.SKIPPED, null);
    }
    CompatibilityScore score =
        CompatibilityAggregator.score(
            signals,
            jobSignals,
            nearest.cosineSimilarity(),
            scoring.weights(),
            scoring.calibration(),
            today);
    if (score.breakdown().completeness() < scoring.weights().minCompleteness()) {
      return new Scoring(Status.INCOMPLETE, null);
    }
    return new Scoring(Status.SCORED, new ScoredPair(nearest, signals, score));
  }

  private void requireVerifiedRecruiter(AccountId recruiter) {
    RecruiterVerification verification =
        trust
            .verificationOf(recruiter)
            .orElseThrow(() -> failed("forbidden", new ForbiddenCapabilityException()));
    switch (verification) {
      case VERIFIED -> {
        // Única confianza con acceso: el resto se niega.
      }
      case UNVERIFIED -> throw denied(Reason.NOT_VERIFIED, "not_verified");
      case SUSPENDED -> throw denied(Reason.SUSPENDED, "suspended");
    }
  }

  // Una vacante en pausa o sin vector no devuelve candidatos, pero sí cuánta cuota queda.
  private SuggestionsResult unavailable(
      SuggestionsResult.Status status, AccountId recruiter, Instant since) {
    int used = views.viewedSince(recruiter, since).size();
    return served(status, List.of(), Math.max(0, policy.viewQuota() - used), 0, 0);
  }

  private SuggestionsResult served(
      SuggestionsResult.Status status,
      List<SuggestedCandidate> candidates,
      int remaining,
      int evaluated,
      int incomplete) {
    String outcome = status.name().toLowerCase(Locale.ROOT);
    metrics.count(USE_CASE, outcome);
    LOG.info(
        "event=suggestions_served outcome={} evaluated={} incomplete={} shown={} Sugeridos de una"
            + " vacante entregados",
        outcome,
        evaluated,
        incomplete,
        candidates.size());
    boolean any = !candidates.isEmpty();
    return new SuggestionsResult(
        status,
        candidates,
        remaining,
        any ? candidates.get(0).score().version() : null,
        any && scoring.calibration().calibrated());
  }

  private SuggestionsDeniedException denied(Reason reason, String outcome) {
    metrics.count(USE_CASE, outcome);
    LOG.warn(
        "event=suggestions_denied outcome={} Sugeridos negados a una cuenta reclutadora", outcome);
    return new SuggestionsDeniedException(reason);
  }

  private <E extends RuntimeException> E failed(String outcome, E error) {
    metrics.count(USE_CASE, outcome);
    return error;
  }
}
