package com.usac.jobseeker.job.application.usecase.managejobrequirements;

import com.usac.jobseeker.job.application.support.JobPostingView;

/** Puerto de entrada: reemplaza los requisitos estructurados de una vacante propia. */
public interface ManageJobRequirementsUseCase {

  JobPostingView execute(ManageJobRequirementsCommand command);
}
