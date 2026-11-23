package com.usac.jobseeker.cv.infrastructure.config;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.GetMyProfessionalProfileService;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.GetMyProfessionalProfileUseCase;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.CandidateCapabilityPort;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.SaveProfessionalProfileService;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.SaveProfessionalProfileUseCase;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcProfessionalProfileRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Cablea los casos de uso de `cv` como beans: `application` no lleva estereotipos (ADR-0015). */
@Configuration(proxyBeanMethods = false)
class CvConfiguration {

  @Bean
  JdbcProfessionalProfileRepository jdbcProfessionalProfileRepository(JdbcClient jdbc) {
    return new JdbcProfessionalProfileRepository(jdbc);
  }

  @Bean
  GetMyProfessionalProfileUseCase getMyProfessionalProfileUseCase(
      JdbcProfessionalProfileRepository repository, UseCaseMetricsPort metrics) {
    return new GetMyProfessionalProfileService(repository, metrics);
  }

  // La bandeja la aporta `app` (ADR-0016) y el reloj de dominio es el del sistema en UTC; los
  // identificadores se generan aquí para que el caso de uso sea determinista en las pruebas.
  @Bean
  SaveProfessionalProfileUseCase saveProfessionalProfileUseCase(
      JdbcProfessionalProfileRepository repository,
      CandidateCapabilityPort capabilities,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics) {
    return new SaveProfessionalProfileService(
        repository, capabilities, events, metrics, Clock.systemUTC(), UUID::randomUUID);
  }
}
