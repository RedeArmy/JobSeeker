package com.usac.jobseeker.job.application.usecase.createjobposting;

import com.usac.jobseeker.job.application.support.JobPostingView;

/** Puerto de entrada: crea la vacante en `DRAFT`; no emite evento, aún no es de nadie más. */
public interface CreateJobPostingUseCase {

  JobPostingView execute(CreateJobPostingCommand command);
}
