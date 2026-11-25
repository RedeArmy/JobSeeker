package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.SuggestionsDeniedException;
import com.usac.jobseeker.common.domain.error.SuggestionsDeniedException.Reason;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.EntityEmbedding;
import com.usac.jobseeker.matching.domain.service.ViewQuota;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
  private final UseCaseMetricsPort metrics;
  private final Clock clock;

  public SuggestCandidatesForJobService(
      RecruiterTrustPort trust,
      SuggestionsSources sources,
      ProfileViewLogPort views,
      SuggestionsPolicy policy,
      UseCaseMetricsPort metrics,
      Clock clock) {
    this.trust = Objects.requireNonNull(trust, "trust");
    this.sources = Objects.requireNonNull(sources, "sources");
    this.views = Objects.requireNonNull(views, "views");
    this.policy = Objects.requireNonNull(policy, "policy");
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
    List<CandidateProfileId> ranked =
        sources
            .retrieval()
            .nearestDiscoverable(
                embedding.get().vector(),
                embedding.get().model(),
                Math.min(query.size(), policy.maxPageSize()))
            .stream()
            .map(NearestProfile::candidate)
            .toList();

    views.lock(query.recruiter());
    Set<CandidateProfileId> alreadyViewed = views.viewedSince(query.recruiter(), since);
    ViewQuota.Allowance allowance = ViewQuota.apply(ranked, alreadyViewed, policy.viewQuota());
    if (allowance.exhausted()) {
      throw denied(Reason.QUOTA_EXCEEDED, "quota_exceeded");
    }
    views.append(query.recruiter(), query.job(), allowance.allowed(), now);
    return served(SuggestionsResult.Status.OK, allowance.allowed(), allowance.remaining());
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
    return served(status, List.of(), Math.max(0, policy.viewQuota() - used));
  }

  private SuggestionsResult served(
      SuggestionsResult.Status status, List<CandidateProfileId> candidates, int remaining) {
    String outcome = status.name().toLowerCase(Locale.ROOT);
    metrics.count(USE_CASE, outcome);
    LOG.info("event=suggestions_served outcome={} Sugeridos de una vacante entregados", outcome);
    return new SuggestionsResult(status, candidates, remaining);
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
