package com.usac.jobseeker.job.application.usecase.setrecruitingstage;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.RecruitingStage;
import java.util.Objects;

/** Cambiar la etapa de reclutamiento de una vacante publicada (BR-JOB-04). */
public record SetRecruitingStageCommand(
    AccountId owner, JobPostingId id, RecruitingStage stage, long expectedVersion) {

  public SetRecruitingStageCommand {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(stage, "stage");
    JobChange.requireValidVersion(expectedVersion);
  }
}
