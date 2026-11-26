package com.usac.jobseeker.matching.application.usecase.explainscore;

import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.embedentity.JobSignalsReader;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.OwnedJobPort;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import java.util.Objects;

/**
 * Quién puede ver su compatibilidad con una vacante (BR-VIS-01): una cuenta con capacidad de
 * candidato, sobre una vacante que los candidatos ven (publicada o cerrada, BR-JOB-11) y que **no
 * es suya**: un reclutador no postula, como candidato, a su propia vacante (BR-ACCT-05). Para quien
 * no puede, la vacante no existe: no se distingue «no existe» de «no es para ti».
 */
public final class CandidateJobAccess {

  private final CandidateAccessPort access;
  private final OwnedJobPort owned;
  private final JobSignalsReader jobs;

  public CandidateJobAccess(CandidateAccessPort access, OwnedJobPort owned, JobSignalsReader jobs) {
    this.access = Objects.requireNonNull(access, "access");
    this.owned = Objects.requireNonNull(owned, "owned");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
  }

  public JobSignals requireVisible(AccountId candidate, JobPostingId job) {
    if (!access.isCandidate(candidate)) {
      throw new ForbiddenCapabilityException();
    }
    JobSignals signals = jobs.read(job).orElseThrow(NotFoundException::new);
    if (!signals.visibleToCandidates() || owned.find(candidate, job).isPresent()) {
      throw new NotFoundException();
    }
    return signals;
  }
}
