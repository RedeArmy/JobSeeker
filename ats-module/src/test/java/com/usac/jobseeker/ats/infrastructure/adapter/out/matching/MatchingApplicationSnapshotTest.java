package com.usac.jobseeker.ats.infrastructure.adapter.out.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.snapshotapplicationprofile.SnapshotApplicationProfileCommand;
import com.usac.jobseeker.matching.application.usecase.snapshotapplicationprofile.SnapshotApplicationProfileUseCase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Al postular, `ats` pide a `matching` la foto con el motivo APPLIED (TD-040). */
class MatchingApplicationSnapshotTest {

  @Test
  void submittingAsksForTheAppliedSnapshotOfThatApplication() {
    List<SnapshotApplicationProfileCommand> asked = new ArrayList<>();
    var adapter =
        new MatchingApplicationSnapshot(
            command -> {
              asked.add(command);
              return new SnapshotApplicationProfileUseCase.SnapshotResult(true, false);
            });
    AccountId candidate = new AccountId(UUID.randomUUID());
    UUID job = UUID.randomUUID();
    ApplicationId application = new ApplicationId(UUID.randomUUID());

    adapter.snapshotOnSubmit(candidate, job, application);

    assertThat(asked)
        .containsExactly(
            new SnapshotApplicationProfileCommand(
                candidate,
                job,
                application.value(),
                SnapshotApplicationProfileCommand.Reason.APPLIED));
  }
}
