package com.usac.jobseeker.matching.infrastructure.adapter.in.job;

import com.usac.jobseeker.matching.application.usecase.purgeexpiredprofileviews.PurgeExpiredProfileViewsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Dispara la purga diaria del registro de perfiles vistos (TD-035). De madrugada, como la purga de
 * la bandeja, y separada de ella para que una no retrase a la otra.
 */
public class ProfileViewRetentionJob {

  private static final Logger LOG = LoggerFactory.getLogger(ProfileViewRetentionJob.class);

  private final PurgeExpiredProfileViewsUseCase purge;

  public ProfileViewRetentionJob(PurgeExpiredProfileViewsUseCase purge) {
    this.purge = purge;
  }

  // Un fallo (la base no responde) no debe tumbar el planificador: se registra y mañana se repite.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  @Scheduled(cron = "${jobseeker.matching.suggestions.view-log-purge-cron:0 30 3 * * *}")
  void run() {
    try {
      purge.execute();
    } catch (RuntimeException failure) {
      LOG.error("event=profile_views_purge_failed Falló la purga de los perfiles vistos", failure);
    }
  }
}
