package com.usac.jobseeker.job.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.application.usecase.getapplicationgate.ApplicationGate;
import com.usac.jobseeker.job.application.usecase.getapplicationgate.GetApplicationGateQuery;
import com.usac.jobseeker.job.application.usecase.getapplicationgate.GetApplicationGateService;
import com.usac.jobseeker.job.application.usecase.setacceptingapplications.SetAcceptingApplicationsCommand;
import com.usac.jobseeker.job.application.usecase.setacceptingapplications.SetAcceptingApplicationsService;
import com.usac.jobseeker.job.application.usecase.transitionjobposting.TransitionJobPostingCommand;
import com.usac.jobseeker.job.application.usecase.transitionjobposting.TransitionJobPostingService;
import com.usac.jobseeker.job.domain.model.JobStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Si se puede postular, en cada estado de la vacante y para su dueño (tdd de `ats` §9). */
class GetApplicationGateTest {

  private final JobFixture f = new JobFixture();
  private final GetApplicationGateService gate =
      new GetApplicationGateService(f.store, (u, o) -> f.metrics.add(u + ":" + o));
  private final AccountId candidate = new AccountId(UUID.randomUUID());

  private ApplicationGate askFor(AccountId account, JobPostingView view) {
    return gate.execute(new GetApplicationGateQuery(account, view.posting().id().value()));
  }

  @Test
  void aPublishedPostingAcceptingApplicationsIsOpen() {
    assertThat(askFor(candidate, JobStates.published(f))).isEqualTo(ApplicationGate.OPEN);
    assertThat(f.metrics).contains("get_application_gate:open");
  }

  // BR-JOB-10: la pausa no oculta la vacante, solo deja de recibir postulaciones.
  @Test
  void aPausedPostingIsVisibleButNotAccepting() {
    JobPostingView published = JobStates.published(f);
    JobPostingView paused =
        new SetAcceptingApplicationsService(f.change, f.clock())
            .execute(
                new SetAcceptingApplicationsCommand(
                    f.owner, published.posting().id(), false, published.posting().version()));

    assertThat(askFor(candidate, paused)).isEqualTo(ApplicationGate.NOT_ACCEPTING);
  }

  @Test
  void aClosedPostingIsVisibleButNotAccepting() {
    assertThat(askFor(candidate, JobStates.closed(f))).isEqualTo(ApplicationGate.NOT_ACCEPTING);
  }

  // BR-JOB-11: un borrador, una archivada o una eliminada no existen para un candidato.
  @Test
  void aPostingACandidateCannotSeeDoesNotExist() {
    assertThat(askFor(candidate, f.draft())).isEqualTo(ApplicationGate.NOT_FOUND);
    assertThat(askFor(candidate, JobStates.archived(f))).isEqualTo(ApplicationGate.NOT_FOUND);

    JobPostingView draft = f.draft();
    new TransitionJobPostingService(f.change, f.gate, f.clock())
        .execute(
            new TransitionJobPostingCommand(
                f.owner, draft.posting().id(), JobStatus.DELETED, draft.posting().version()));
    assertThat(askFor(candidate, draft)).isEqualTo(ApplicationGate.NOT_FOUND);
    assertThat(gate.execute(new GetApplicationGateQuery(candidate, UUID.randomUUID())))
        .isEqualTo(ApplicationGate.NOT_FOUND);
  }

  // BR-ACCT-05: el reclutador no postula a lo suyo, en ningún estado que aún pueda ver.
  @Test
  void theOwnerCannotApplyToTheirOwnPosting() {
    assertThat(askFor(f.owner, JobStates.published(f))).isEqualTo(ApplicationGate.OWN_JOB);
    assertThat(askFor(f.owner, f.draft())).isEqualTo(ApplicationGate.OWN_JOB);
    assertThat(f.metrics).contains("get_application_gate:own_job");
  }
}
