package com.usac.jobseeker.ats.infrastructure.adapter.out.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.usecase.getownedjobstate.OwnedJobState;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** La propiedad de la vacante se pregunta a `job` con su caso de uso (regla 1 del TDD). */
class JobOwnershipTest {

  private final AccountId recruiter = new AccountId(UUID.randomUUID());
  private final UUID job = UUID.randomUUID();

  @Test
  void ownsOnlyWhenJobAnswersWithTheState() {
    var owned =
        new JobOwnership(
            query -> {
              assertThat(query.owner()).isEqualTo(recruiter);
              assertThat(query.jobPostingId()).isEqualTo(job);
              return Optional.of(new OwnedJobState(job, "PUBLISHED", "HIRING"));
            });
    var foreign = new JobOwnership(query -> Optional.empty());

    assertThat(owned.owns(recruiter, job)).isTrue();
    assertThat(foreign.owns(recruiter, job)).isFalse();
  }
}
