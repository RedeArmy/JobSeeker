package com.usac.jobseeker.matching.application.usecase.recomputescoresforentity;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.ScoreCalculator;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.ScoringSources;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import com.usac.jobseeker.matching.domain.model.StoredScore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Se acota a `maxPairs` por
 * entidad y pasada, los más recientes primero: una entidad con muchísimos pares no puede retener al
 * trabajador, y los que quedan fuera se actualizan al verse o con la migración de versión. Lo que
 * no se puede recalcular (falta un vector, la vacante no se lee) se deja como está: se sirve el
 * último puntaje con aviso (NFR-AVAIL-02) y nunca uno a medias. Ni el registro ni la métrica llevan
 * identificadores (BR-DATA-01).
 */
public class RecomputeScoresForEntityService implements RecomputeScoresForEntityUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RecomputeScoresForEntityService.class);
  private static final String USE_CASE = "recompute_scores_for_entity";

  private final ScoringSources sources;
  private final ScoreCalculator calculator;
  private final int maxPairs;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;

  public RecomputeScoresForEntityService(
      ScoringSources sources,
      ScoreCalculator calculator,
      int maxPairs,
      UseCaseMetricsPort metrics,
      Clock clock) {
    if (maxPairs <= 0) {
      throw new IllegalArgumentException("el máximo de pares por entidad debe ser positivo");
    }
    this.sources = Objects.requireNonNull(sources, "sources");
    this.calculator = Objects.requireNonNull(calculator, "calculator");
    this.maxPairs = maxPairs;
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional
  public int execute(RecomputeScoresCommand command) {
    int recomputed =
        switch (command.entity()) {
          case PROFILE -> forProfile(new AccountId(command.key()));
          case JOB -> forJob(new JobPostingId(command.key()));
        };
    metrics.count(USE_CASE, recomputed > 0 ? "recomputed" : "nothing");
    return recomputed;
  }

  private int forProfile(AccountId account) {
    Optional<CandidateProfileId> profile = sources.directory().profileOf(account);
    if (profile.isEmpty()) {
      return 0;
    }
    Set<JobPostingId> applied =
        new HashSet<>(sources.store().appliedJobsOfProfile(profile.get(), maxPairs));
    List<JobPostingId> jobs =
        union(sources.store().jobsOfProfile(profile.get(), maxPairs), applied);
    Optional<CandidateSignals> signals =
        jobs.isEmpty() ? Optional.empty() : sources.candidates().read(account);
    if (signals.isEmpty()) {
      return 0;
    }
    Map<JobPostingId, Double> cosines = sources.similarity().cosinesForProfile(profile.get(), jobs);
    // Todas las vacantes en una lectura, no una por par: un perfil con 200 pares costaba 400
    // consultas por cada edición (coding-standards §8.1).
    Map<JobPostingId, JobSignals> jobSignals = sources.jobs().readAll(jobs);
    Instant now = clock.instant();
    List<StoredScore> fresh = new ArrayList<>();
    for (JobPostingId job : jobs) {
      JobSignals signalsOfJob = jobSignals.get(job);
      Double cosine = cosines.get(job);
      if (signalsOfJob != null && cosine != null) {
        fresh.add(
            recomputed(
                signals.get(),
                profile.get(),
                job,
                signalsOfJob,
                cosine,
                applied.contains(job),
                now));
      }
    }
    return save(fresh, jobs.size());
  }

  private int forJob(JobPostingId job) {
    Optional<JobSignals> jobSignals = sources.jobs().read(job);
    Set<CandidateProfileId> applied =
        jobSignals.isEmpty()
            ? Set.of()
            : new HashSet<>(sources.store().appliedProfilesOfJob(job, maxPairs));
    List<CandidateProfileId> profiles =
        jobSignals.isEmpty()
            ? List.of()
            : union(sources.store().profilesOfJob(job, maxPairs), applied);
    if (profiles.isEmpty()) {
      return 0;
    }
    Map<CandidateProfileId, AccountId> accountOf = sources.directory().accountsOf(profiles);
    Map<AccountId, CandidateSignals> signalsOf = sources.candidates().readAll(accountOf.values());
    Map<CandidateProfileId, Double> cosines = sources.similarity().cosinesForJob(job, profiles);
    Instant now = clock.instant();
    List<StoredScore> fresh = new ArrayList<>();
    for (CandidateProfileId profile : profiles) {
      AccountId account = accountOf.get(profile);
      CandidateSignals signals = account == null ? null : signalsOf.get(account);
      Double cosine = cosines.get(profile);
      if (signals != null && cosine != null) {
        fresh.add(
            recomputed(
                signals, profile, job, jobSignals.get(), cosine, applied.contains(profile), now));
      }
    }
    return save(fresh, profiles.size());
  }

  // Se pasa el motivo MÁS DÉBIL a propósito: el almacén conserva el más fuerte que el par ya tenga
  // (un postulado sigue postulado, un par visto por el candidato sigue visto). La excepción es un
  // par postulado: puede no tener fila todavía, si al postular faltaba el vector, y debe nacer como
  // APPLIED (ADR-0031), no como sugerido.
  private StoredScore recomputed(
      CandidateSignals signals,
      CandidateProfileId profile,
      JobPostingId job,
      JobSignals jobSignals,
      double cosine,
      boolean applied,
      Instant now) {
    return new StoredScore(
        profile,
        job,
        calculator.score(signals, jobSignals, cosine),
        applied ? ScoreReason.APPLIED : ScoreReason.SUGGESTED,
        now);
  }

  // Los pares con puntaje vigente más los postulados, sin repetir y en un orden estable.
  private static <T> List<T> union(List<T> stored, Set<T> applied) {
    Set<T> all = new LinkedHashSet<>(stored);
    all.addAll(applied);
    return List.copyOf(all);
  }

  private int save(List<StoredScore> fresh, int pairs) {
    if (!fresh.isEmpty()) {
      sources.store().upsertCurrent(fresh);
    }
    if (fresh.size() < pairs) {
      LOG.info(
          "event=scores_not_recomputed pending={} Pares sin recalcular por falta de un vector o de"
              + " lectura; se sirve el último puntaje",
          pairs - fresh.size());
    }
    return fresh.size();
  }
}
