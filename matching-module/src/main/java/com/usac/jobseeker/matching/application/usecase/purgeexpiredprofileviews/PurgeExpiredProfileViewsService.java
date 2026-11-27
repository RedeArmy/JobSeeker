package com.usac.jobseeker.matching.application.usecase.purgeexpiredprofileviews;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Sin transacción propia a
 * propósito: cada tanda es una sentencia que confirma sola, así una purga de millones de filas no
 * es una transacción enorme y, si se corta, lo borrado queda borrado y el resto sigue mañana.
 */
public class PurgeExpiredProfileViewsService implements PurgeExpiredProfileViewsUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(PurgeExpiredProfileViewsService.class);
  private static final String USE_CASE = "purge_expired_profile_views";

  private final ProfileViewRetentionPort views;
  private final ProfileViewRetentionPolicy policy;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;

  public PurgeExpiredProfileViewsService(
      ProfileViewRetentionPort views,
      ProfileViewRetentionPolicy policy,
      UseCaseMetricsPort metrics,
      Clock clock) {
    this.views = Objects.requireNonNull(views, "views");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public int execute() {
    Instant cutoff = clock.instant().minus(policy.retention());
    int total = 0;
    for (int batch = 0; batch < policy.maxBatches(); batch++) {
      int purged = views.purgeViewedBefore(cutoff, policy.batchSize());
      total += purged;
      if (purged < policy.batchSize()) {
        break;
      }
    }
    metrics.count(USE_CASE, total > 0 ? "purged" : "nothing");
    LOG.info(
        "event=profile_views_purged purged={} Purga de retención de los perfiles vistos", total);
    return total;
  }
}
