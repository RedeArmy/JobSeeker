package com.usac.jobseeker.job.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.infrastructure.JobPostgres;
import com.usac.jobseeker.job.infrastructure.adapter.out.persistence.JdbcJobPostingRepository;
import com.usac.jobseeker.job.infrastructure.adapter.out.persistence.JdbcJobRequirementsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** El cableado de los casos de uso se construye con sus puertos reales. */
class JobConfigurationTest {

  private final JobConfiguration config = new JobConfiguration();
  private final JdbcClient jdbc = JdbcClient.create(JobPostgres.dataSource());

  @Test
  void everyUseCaseIsBuiltFromItsAdapters() {
    JdbcJobPostingRepository postings = config.jdbcJobPostingRepository(jdbc);
    JdbcJobRequirementsRepository requirements = config.jdbcJobRequirementsRepository(jdbc);
    JobChange change =
        config.jobChange(
            postings, requirements, new AllowAll(), event -> {}, (useCase, outcome) -> {});

    assertThat(config.createJobPostingUseCase(postings, change)).isNotNull();
    assertThat(config.updateJobPostingUseCase(change)).isNotNull();
    assertThat(config.getJobPostingUseCase(postings, requirements, new AllowAll(), (u, o) -> {}))
        .isNotNull();
    assertThat(config.manageJobRequirementsUseCase(requirements, change)).isNotNull();
    var gate =
        config.publishingGate(
            account -> RecruiterVerification.VERIFIED,
            postings,
            new JobProperties(new JobProperties.Publishing(2, 100)));
    assertThat(config.transitionJobPostingUseCase(change, gate)).isNotNull();
    assertThat(config.setAcceptingApplicationsUseCase(change)).isNotNull();
    assertThat(config.setRecruitingStageUseCase(change)).isNotNull();
    assertThat(config.getJobSignalsUseCase(postings, requirements, (u, o) -> {})).isNotNull();
  }

  private static final class AllowAll
      implements com.usac.jobseeker.job.application.support.CapabilityPort {

    @Override
    public boolean isRecruiter(com.usac.jobseeker.common.domain.model.AccountId accountId) {
      return true;
    }

    @Override
    public boolean isCandidate(com.usac.jobseeker.common.domain.model.AccountId accountId) {
      return true;
    }
  }
}
