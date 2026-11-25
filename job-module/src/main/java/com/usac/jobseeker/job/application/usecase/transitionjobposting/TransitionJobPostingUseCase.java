package com.usac.jobseeker.job.application.usecase.transitionjobposting;

import com.usac.jobseeker.job.application.support.JobPostingView;

/** Puerto de entrada: ejecuta una transición manual válida de la máquina de estados. */
public interface TransitionJobPostingUseCase {

  JobPostingView execute(TransitionJobPostingCommand command);
}
