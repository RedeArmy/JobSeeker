package com.usac.jobseeker.job.application.usecase.setrecruitingstage;

import com.usac.jobseeker.job.application.support.JobPostingView;

/** Puerto de entrada: cambia `HIRING`/`REVIEWING`, independiente del estado (BR-JOB-04). */
public interface SetRecruitingStageUseCase {

  JobPostingView execute(SetRecruitingStageCommand command);
}
