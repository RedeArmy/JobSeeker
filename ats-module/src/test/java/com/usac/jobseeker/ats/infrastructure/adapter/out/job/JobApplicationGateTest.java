package com.usac.jobseeker.ats.infrastructure.adapter.out.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.ats.application.usecase.submitapplication.JobApplicationGatePort;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.usecase.getapplicationgate.ApplicationGate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Cada respuesta de `job` tiene su equivalente en `ats`, sin que ninguna se pierda. */
class JobApplicationGateTest {

  @Test
  void everyAnswerOfJobIsTranslated() {
    AccountId applicant = new AccountId(UUID.randomUUID());
    UUID job = UUID.randomUUID();
    for (ApplicationGate answer : ApplicationGate.values()) {
      var adapter =
          new JobApplicationGate(
              query -> {
                assertThat(query.applicant()).isEqualTo(applicant);
                assertThat(query.jobPostingId()).isEqualTo(job);
                return answer;
              });

      assertThat(adapter.check(applicant, job))
          .isEqualTo(JobApplicationGatePort.Gate.valueOf(answer.name()));
    }
  }
}
