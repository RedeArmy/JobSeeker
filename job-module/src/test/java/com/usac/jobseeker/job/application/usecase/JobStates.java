package com.usac.jobseeker.job.application.usecase;

import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.application.usecase.managejobrequirements.ManageJobRequirementsCommand;
import com.usac.jobseeker.job.application.usecase.managejobrequirements.ManageJobRequirementsService;
import com.usac.jobseeker.job.application.usecase.transitionjobposting.TransitionJobPostingCommand;
import com.usac.jobseeker.job.application.usecase.transitionjobposting.TransitionJobPostingService;
import com.usac.jobseeker.job.domain.model.Initiator;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobRequirementDraft;
import com.usac.jobseeker.job.domain.model.JobStatus;
import com.usac.jobseeker.job.domain.model.RequirementType;
import java.util.List;

/** Lleva una vacante a cada estado usando los casos de uso reales, como lo haría un cliente. */
final class JobStates {

  private JobStates() {}

  static JobRequirementDraft javaSkill() {
    return new JobRequirementDraft(RequirementType.SKILL, true, "Java", 3, null, null, null, null);
  }

  /** Una vacante publicada, con una habilidad: la mínima para poder publicar (BR-JOB-02). */
  static JobPostingView published(JobFixture f) {
    JobPostingView draft = f.draft();
    var requirements =
        new ManageJobRequirementsService(f.store, f.change, f.clock())
            .execute(
                new ManageJobRequirementsCommand(
                    f.owner,
                    draft.posting().id(),
                    draft.posting().version(),
                    List.of(javaSkill())));
    return transition(f, requirements, JobStatus.PUBLISHED);
  }

  static JobPostingView closed(JobFixture f) {
    return transition(f, published(f), JobStatus.CLOSED);
  }

  /** Archivada: el archivado es del sistema, así que el paso final se hace sobre el dominio. */
  static JobPostingView archived(JobFixture f) {
    JobPostingView closed = closed(f);
    JobPosting archived =
        closed
            .posting()
            .transition(JobStatus.ARCHIVED, Initiator.SYSTEM, true, f.clock().instant());
    f.store.postings.put(archived.id(), archived);
    return new JobPostingView(archived, closed.requirements(), true);
  }

  static JobPostingView transition(JobFixture f, JobPostingView from, JobStatus to) {
    return new TransitionJobPostingService(f.change, f.clock())
        .execute(
            new TransitionJobPostingCommand(
                f.owner, from.posting().id(), to, from.posting().version()));
  }

  static RequirementType skill() {
    return RequirementType.SKILL;
  }
}
