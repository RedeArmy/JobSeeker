package com.usac.jobseeker.job.infrastructure.config;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.job.application.support.CapabilityPort;
import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.application.support.PublishingGate;
import com.usac.jobseeker.job.application.support.PublishingPolicy;
import com.usac.jobseeker.job.application.support.RecruiterStandingPort;
import com.usac.jobseeker.job.application.usecase.createjobposting.CreateJobPostingService;
import com.usac.jobseeker.job.application.usecase.createjobposting.CreateJobPostingUseCase;
import com.usac.jobseeker.job.application.usecase.getapplicationgate.GetApplicationGateService;
import com.usac.jobseeker.job.application.usecase.getapplicationgate.GetApplicationGateUseCase;
import com.usac.jobseeker.job.application.usecase.getjobposting.GetJobPostingService;
import com.usac.jobseeker.job.application.usecase.getjobposting.GetJobPostingUseCase;
import com.usac.jobseeker.job.application.usecase.getjobsignals.GetJobSignalsService;
import com.usac.jobseeker.job.application.usecase.getownedjobstate.GetOwnedJobStateService;
import com.usac.jobseeker.job.application.usecase.getownedjobstate.GetOwnedJobStateUseCase;
import com.usac.jobseeker.job.application.usecase.getpostingsummaries.GetPostingSummariesService;
import com.usac.jobseeker.job.application.usecase.getpostingsummaries.GetPostingSummariesUseCase;
import com.usac.jobseeker.job.application.usecase.managejobrequirements.ManageJobRequirementsService;
import com.usac.jobseeker.job.application.usecase.managejobrequirements.ManageJobRequirementsUseCase;
import com.usac.jobseeker.job.application.usecase.setacceptingapplications.SetAcceptingApplicationsService;
import com.usac.jobseeker.job.application.usecase.setacceptingapplications.SetAcceptingApplicationsUseCase;
import com.usac.jobseeker.job.application.usecase.setrecruitingstage.SetRecruitingStageService;
import com.usac.jobseeker.job.application.usecase.setrecruitingstage.SetRecruitingStageUseCase;
import com.usac.jobseeker.job.application.usecase.transitionjobposting.TransitionJobPostingService;
import com.usac.jobseeker.job.application.usecase.transitionjobposting.TransitionJobPostingUseCase;
import com.usac.jobseeker.job.application.usecase.updatejobposting.UpdateJobPostingService;
import com.usac.jobseeker.job.application.usecase.updatejobposting.UpdateJobPostingUseCase;
import com.usac.jobseeker.job.infrastructure.adapter.out.persistence.JdbcJobPostingRepository;
import com.usac.jobseeker.job.infrastructure.adapter.out.persistence.JdbcJobRequirementsRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Cablea los casos de uso de `job` como beans: `application` no lleva estereotipos (ADR-0015). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobProperties.class)
class JobConfiguration {

  @Bean
  JdbcJobPostingRepository jdbcJobPostingRepository(JdbcClient jdbc) {
    return new JdbcJobPostingRepository(jdbc);
  }

  @Bean
  JdbcJobRequirementsRepository jdbcJobRequirementsRepository(JdbcClient jdbc) {
    return new JdbcJobRequirementsRepository(jdbc);
  }

  // La bandeja la aporta `app` (ADR-0016) y el reloj de dominio es el del sistema en UTC; los
  // identificadores se generan aquí para que el caso de uso sea determinista en las pruebas.
  @Bean
  JobChange jobChange(
      JdbcJobPostingRepository postings,
      JdbcJobRequirementsRepository requirements,
      CapabilityPort capabilities,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics) {
    return new JobChange(postings, requirements, capabilities, events, metrics, UUID::randomUUID);
  }

  @Bean
  CreateJobPostingUseCase createJobPostingUseCase(
      JdbcJobPostingRepository postings, JobChange change) {
    return new CreateJobPostingService(postings, change, Clock.systemUTC(), UUID::randomUUID);
  }

  @Bean
  UpdateJobPostingUseCase updateJobPostingUseCase(JobChange change) {
    return new UpdateJobPostingService(change, Clock.systemUTC());
  }

  @Bean
  GetJobPostingUseCase getJobPostingUseCase(
      JdbcJobPostingRepository postings,
      JdbcJobRequirementsRepository requirements,
      CapabilityPort capabilities,
      UseCaseMetricsPort metrics) {
    return new GetJobPostingService(postings, requirements, capabilities, metrics);
  }

  @Bean
  ManageJobRequirementsUseCase manageJobRequirementsUseCase(
      JdbcJobRequirementsRepository requirements, JobChange change) {
    return new ManageJobRequirementsService(requirements, change, Clock.systemUTC());
  }

  @Bean
  PublishingGate publishingGate(
      RecruiterStandingPort standings,
      JdbcJobPostingRepository postings,
      JobProperties properties) {
    JobProperties.Publishing limits = properties.publishing();
    return new PublishingGate(
        standings,
        postings,
        new PublishingPolicy(limits.unverifiedMaxPublished(), limits.verifiedMaxPublished()));
  }

  @Bean
  TransitionJobPostingUseCase transitionJobPostingUseCase(JobChange change, PublishingGate gate) {
    return new TransitionJobPostingService(change, gate, Clock.systemUTC());
  }

  @Bean
  SetAcceptingApplicationsUseCase setAcceptingApplicationsUseCase(JobChange change) {
    return new SetAcceptingApplicationsService(change, Clock.systemUTC());
  }

  @Bean
  SetRecruitingStageUseCase setRecruitingStageUseCase(JobChange change) {
    return new SetRecruitingStageService(change, Clock.systemUTC());
  }

  // Interna para `tracker`: título y estado, también de las eliminadas (BR-JOB-09).
  @Bean
  GetPostingSummariesUseCase getPostingSummariesUseCase(
      JdbcJobPostingRepository postings, UseCaseMetricsPort metrics) {
    return new GetPostingSummariesService(postings, metrics);
  }

  // Interna para `ats`: si una cuenta puede postular (tdd/ats-tracker-modules.md §5).
  @Bean
  GetApplicationGateUseCase getApplicationGateUseCase(
      JdbcJobPostingRepository postings, UseCaseMetricsPort metrics) {
    return new GetApplicationGateService(postings, metrics);
  }

  @Bean
  GetOwnedJobStateUseCase getOwnedJobStateUseCase(
      JdbcJobPostingRepository postings, UseCaseMetricsPort metrics) {
    return new GetOwnedJobStateService(postings, metrics);
  }

  // Un solo bean para la lectura individual y la de lotes: dos instancias darían a Spring dos
  // candidatos para cada interfaz (coding-standards §8.1).
  @Bean
  GetJobSignalsService getJobSignalsUseCase(
      JdbcJobPostingRepository postings,
      JdbcJobRequirementsRepository requirements,
      UseCaseMetricsPort metrics) {
    return new GetJobSignalsService(postings, requirements, metrics);
  }
}
