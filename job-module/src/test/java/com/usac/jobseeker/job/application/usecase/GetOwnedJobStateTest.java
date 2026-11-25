package com.usac.jobseeker.job.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.usecase.getownedjobstate.GetOwnedJobStateQuery;
import com.usac.jobseeker.job.application.usecase.getownedjobstate.GetOwnedJobStateService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El estado de una vacante solo se entrega a su dueño (BR-VIS-03). */
class GetOwnedJobStateTest {

  private final JobFixture f = new JobFixture();
  private final GetOwnedJobStateService state =
      new GetOwnedJobStateService(f.store, (u, o) -> f.metrics.add(u + ":" + o));

  @Test
  void theOwnerGetsTheStatusAndTheStageAsText() {
    UUID id = f.draft().posting().id().value();

    var result = state.execute(new GetOwnedJobStateQuery(f.owner, id));

    assertThat(result).isPresent();
    assertThat(result.get().jobPostingId()).isEqualTo(id);
    assertThat(result.get().status()).isEqualTo("DRAFT");
    assertThat(result.get().recruitingStage()).isEqualTo("HIRING");
    assertThat(f.metrics).contains("get_owned_job_state:found");
  }

  // Para quien no es dueño la vacante no existe, y no se distingue de una inexistente.
  @Test
  void anotherAccountGetsTheSameAnswerAsAMissingPosting() {
    UUID id = f.draft().posting().id().value();
    AccountId stranger = new AccountId(UUID.randomUUID());

    assertThat(state.execute(new GetOwnedJobStateQuery(stranger, id))).isEmpty();
    assertThat(state.execute(new GetOwnedJobStateQuery(f.owner, UUID.randomUUID()))).isEmpty();
    assertThat(f.metrics).contains("get_owned_job_state:absent");
  }
}
