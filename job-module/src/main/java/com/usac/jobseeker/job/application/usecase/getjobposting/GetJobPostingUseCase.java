package com.usac.jobseeker.job.application.usecase.getjobposting;

import com.usac.jobseeker.job.application.support.JobPostingView;

/** Puerto de entrada: una vacante, para su dueño o para un candidato según su visibilidad. */
public interface GetJobPostingUseCase {

  JobPostingView execute(GetJobPostingQuery query);
}
