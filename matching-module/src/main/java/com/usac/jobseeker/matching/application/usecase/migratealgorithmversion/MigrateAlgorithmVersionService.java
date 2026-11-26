package com.usac.jobseeker.matching.application.usecase.migratealgorithmversion;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.ScoreCalculator;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.ScoringSources;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import com.usac.jobseeker.matching.domain.model.ScoreSnapshot;
import com.usac.jobseeker.matching.domain.model.SnapshotReason;
import com.usac.jobseeker.matching.domain.model.StoredScore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Cada pasada toma hasta
 * `batchSize` pares, los más viejos primero. Lo que no se puede recalcular no debe ocupar el primer
 * lugar en cada pasada: si ya no hay perfil o vacante legible y el par no está postulado, se retira
 * (la historia queda en las fotos); si está postulado se conserva (BR-JOB-09, BR-DATA-05) y se
 * aplaza; y si solo falta un vector, se aplaza hasta que exista.
 */
public class MigrateAlgorithmVersionService implements MigrateAlgorithmVersionUseCase {

  private static final String USE_CASE = "migrate_algorithm_version";

  private final ScoringSources sources;
  private final ScoreCalculator calculator;
  private final int batchSize;
  private final Duration snapshotRetention;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;

  public MigrateAlgorithmVersionService(
      ScoringSources sources,
      ScoreCalculator calculator,
      int batchSize,
      Duration snapshotRetention,
      UseCaseMetricsPort metrics,
      Clock clock) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("el tamaño del lote debe ser positivo");
    }
    this.sources = Objects.requireNonNull(sources, "sources");
    this.calculator = Objects.requireNonNull(calculator, "calculator");
    this.batchSize = batchSize;
    this.snapshotRetention = Objects.requireNonNull(snapshotRetention, "snapshotRetention");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional
  public int execute() {
    List<StoredScore> stale = sources.store().staleVersion(calculator.version().value(), batchSize);
    Instant now = clock.instant();
    int migrated = 0;
    for (StoredScore old : stale) {
      if (migrate(old, now)) {
        migrated++;
      }
    }
    if (!stale.isEmpty()) {
      metrics.count(USE_CASE, "processed");
    }
    return stale.size();
  }

  @Override
  @Transactional
  public int purgeExpiredSnapshots() {
    int purged = sources.store().purgeSnapshotsOlderThan(clock.instant().minus(snapshotRetention));
    if (purged > 0) {
      metrics.count(USE_CASE, "purged");
    }
    return purged;
  }

  // Devuelve si se recalculó; si no, el par ya quedó retirado o aplazado.
  private boolean migrate(StoredScore old, Instant now) {
    Optional<AccountId> account = sources.directory().accountOf(old.profile());
    Optional<CandidateSignals> signals =
        account.isEmpty() ? Optional.empty() : sources.candidates().read(account.get());
    Optional<JobSignals> job = sources.jobs().read(old.job());
    if (signals.isEmpty() || job.isEmpty()) {
      retireOrDefer(old, now);
      return false;
    }
    Optional<Double> cosine = sources.similarity().cosine(old.profile(), old.job());
    if (cosine.isEmpty()) {
      sources.store().touch(old.profile(), old.job(), now);
      return false;
    }
    // La foto es la del puntaje ANTERIOR: es lo que permite comparar una versión con otra.
    sources
        .store()
        .insertSnapshot(
            new ScoreSnapshot(
                UUID.randomUUID(),
                old.profile(),
                old.job(),
                SnapshotReason.VERSION_CHANGED,
                old.score(),
                now));
    sources
        .store()
        .upsertCurrent(
            List.of(
                new StoredScore(
                    old.profile(),
                    old.job(),
                    calculator.score(signals.get(), job.get(), cosine.get()),
                    old.reason(),
                    now)));
    return true;
  }

  private void retireOrDefer(StoredScore old, Instant now) {
    if (old.reason() == ScoreReason.APPLIED) {
      sources.store().touch(old.profile(), old.job(), now);
    } else {
      sources.store().deleteCurrent(old.profile(), old.job());
    }
  }
}
