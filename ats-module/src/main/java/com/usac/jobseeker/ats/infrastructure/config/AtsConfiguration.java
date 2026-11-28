package com.usac.jobseeker.ats.infrastructure.config;

import com.usac.jobseeker.ats.application.support.ActorCapabilityPort;
import com.usac.jobseeker.ats.application.support.ApplicantProfilesPort;
import com.usac.jobseeker.ats.application.support.ApplicantScoresPort;
import com.usac.jobseeker.ats.application.support.ApplicationSnapshotPort;
import com.usac.jobseeker.ats.application.support.OwnedJobPort;
import com.usac.jobseeker.ats.application.usecase.getapplication.GetApplicationService;
import com.usac.jobseeker.ats.application.usecase.getapplication.GetApplicationUseCase;
import com.usac.jobseeker.ats.application.usecase.listcandidateapplications.ListCandidateApplicationsService;
import com.usac.jobseeker.ats.application.usecase.listcandidateapplications.ListCandidateApplicationsUseCase;
import com.usac.jobseeker.ats.application.usecase.listjobapplications.ListJobApplicationsService;
import com.usac.jobseeker.ats.application.usecase.listjobapplications.ListJobApplicationsUseCase;
import com.usac.jobseeker.ats.application.usecase.submitapplication.ApplicantReadinessPort;
import com.usac.jobseeker.ats.application.usecase.submitapplication.JobApplicationGatePort;
import com.usac.jobseeker.ats.application.usecase.submitapplication.SubmitApplicationService;
import com.usac.jobseeker.ats.application.usecase.submitapplication.SubmitApplicationUseCase;
import com.usac.jobseeker.ats.application.usecase.withdrawapplication.WithdrawApplicationService;
import com.usac.jobseeker.ats.application.usecase.withdrawapplication.WithdrawApplicationUseCase;
import com.usac.jobseeker.ats.infrastructure.adapter.out.persistence.JdbcApplicationRepository;
import com.usac.jobseeker.common.application.idempotency.IdempotencyKeysPort;
import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Cableado de `ats` (ADR-0015, ARCH-02): los casos de uso no llevan estereotipos y se registran
 * aquí. Los adaptadores que nombran tipos de otros módulos se registran solos; esta clase solo ve
 * los puertos propios de `ats`.
 */
@Configuration(proxyBeanMethods = false)
class AtsConfiguration {

  @Bean
  JdbcApplicationRepository jdbcApplicationRepository(JdbcClient jdbc) {
    return new JdbcApplicationRepository(jdbc);
  }

  // La bandeja y las claves de idempotencia las aporta `app` (ADR-0016, TD-010). El reloj es el
  // del sistema en UTC y los identificadores se generan aquí para que el caso de uso sea
  // determinista en las pruebas.
  @Bean
  SubmitApplicationUseCase submitApplicationUseCase(
      ActorCapabilityPort capabilities,
      IdempotencyKeysPort idempotency,
      JobApplicationGatePort gate,
      ApplicantReadinessPort readiness,
      JdbcApplicationRepository store,
      ApplicationSnapshotPort snapshots,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics) {
    return new SubmitApplicationService(
        new SubmitApplicationService.Ports(
            capabilities, idempotency, gate, readiness, store, snapshots, events),
        metrics,
        UUID::randomUUID,
        Clock.systemUTC());
  }

  @Bean
  WithdrawApplicationUseCase withdrawApplicationUseCase(
      ActorCapabilityPort capabilities,
      JdbcApplicationRepository store,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics) {
    return new WithdrawApplicationService(
        capabilities, store, events, metrics, UUID::randomUUID, Clock.systemUTC());
  }

  // Interna para `tracker`, que no tiene tablas (ADR-0027).
  @Bean
  ListCandidateApplicationsUseCase listCandidateApplicationsUseCase(
      JdbcApplicationRepository store, UseCaseMetricsPort metrics) {
    return new ListCandidateApplicationsService(store, metrics);
  }

  // Regla 6 del TDD: por encima de este tope el pipeline necesitaría la proyección de D4.
  static final int PIPELINE_CAP = 1000;

  @Bean
  ListJobApplicationsUseCase listJobApplicationsUseCase(
      ActorCapabilityPort capabilities,
      OwnedJobPort jobs,
      JdbcApplicationRepository store,
      ApplicantScoresPort scores,
      ApplicantProfilesPort profiles,
      UseCaseMetricsPort metrics) {
    return new ListJobApplicationsService(
        capabilities, jobs, store, scores, profiles, metrics, PIPELINE_CAP);
  }

  @Bean
  GetApplicationUseCase getApplicationUseCase(
      ActorCapabilityPort capabilities,
      OwnedJobPort jobs,
      JdbcApplicationRepository store,
      ApplicantScoresPort scores,
      ApplicantProfilesPort profiles,
      UseCaseMetricsPort metrics) {
    return new GetApplicationService(capabilities, jobs, store, scores, profiles, metrics);
  }
}
