package com.usac.jobseeker.ats.infrastructure.adapter.out.matching;

import com.usac.jobseeker.ats.application.support.ApplicationSnapshotPort;
import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.snapshotapplicationprofile.SnapshotApplicationProfileCommand;
import com.usac.jobseeker.matching.application.usecase.snapshotapplicationprofile.SnapshotApplicationProfileUseCase;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pide a `matching` la foto de la postulación (TD-040), con su caso de uso público (ARCH-04). Ese
 * caso de uso es transaccional con propagación por defecto, así que se une a la transacción de
 * postular: si falla, la postulación tampoco queda.
 */
@Component
public class MatchingApplicationSnapshot implements ApplicationSnapshotPort {

  private final SnapshotApplicationProfileUseCase snapshot;

  public MatchingApplicationSnapshot(SnapshotApplicationProfileUseCase snapshot) {
    this.snapshot = snapshot;
  }

  @Override
  public void snapshotOnSubmit(AccountId candidate, UUID jobPostingId, ApplicationId application) {
    snapshot.execute(
        new SnapshotApplicationProfileCommand(
            candidate,
            jobPostingId,
            application.value(),
            SnapshotApplicationProfileCommand.Reason.APPLIED));
  }
}
