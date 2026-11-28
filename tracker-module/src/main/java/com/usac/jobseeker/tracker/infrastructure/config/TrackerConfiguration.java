package com.usac.jobseeker.tracker.infrastructure.config;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.tracker.application.support.CandidateApplicationsPort;
import com.usac.jobseeker.tracker.application.support.CandidateCapabilityPort;
import com.usac.jobseeker.tracker.application.support.MyApplications;
import com.usac.jobseeker.tracker.application.support.PostingSummariesPort;
import com.usac.jobseeker.tracker.application.usecase.getmyapplication.GetMyApplicationService;
import com.usac.jobseeker.tracker.application.usecase.getmyapplication.GetMyApplicationUseCase;
import com.usac.jobseeker.tracker.application.usecase.listmyapplications.ListMyApplicationsService;
import com.usac.jobseeker.tracker.application.usecase.listmyapplications.ListMyApplicationsUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cableado de `tracker` (ADR-0015, ARCH-02). Sin persistencia: `tracker` no tiene tablas
 * (ADR-0027). Los adaptadores que nombran tipos de otros módulos se registran solos.
 */
@Configuration(proxyBeanMethods = false)
class TrackerConfiguration {

  @Bean
  MyApplications myApplications(
      CandidateCapabilityPort capabilities,
      CandidateApplicationsPort applications,
      PostingSummariesPort postings) {
    return new MyApplications(capabilities, applications, postings);
  }

  @Bean
  ListMyApplicationsUseCase listMyApplicationsUseCase(
      MyApplications mine, UseCaseMetricsPort metrics) {
    return new ListMyApplicationsService(mine, metrics);
  }

  @Bean
  GetMyApplicationUseCase getMyApplicationUseCase(MyApplications mine, UseCaseMetricsPort metrics) {
    return new GetMyApplicationService(mine, metrics);
  }
}
