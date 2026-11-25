package com.usac.jobseeker.job.application.usecase.updatejobposting;

import com.usac.jobseeker.job.application.support.JobPostingView;

/** Puerto de entrada: edita los datos de una vacante propia (borrador, publicada o cerrada). */
public interface UpdateJobPostingUseCase {

  JobPostingView execute(UpdateJobPostingCommand command);
}
