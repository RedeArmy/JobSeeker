package com.usac.jobseeker.job.application.usecase.setacceptingapplications;

import com.usac.jobseeker.job.application.support.JobPostingView;

/** Puerto de entrada: cambia `acceptingApplications`, independiente del estado (BR-JOB-10). */
public interface SetAcceptingApplicationsUseCase {

  JobPostingView execute(SetAcceptingApplicationsCommand command);
}
