package com.usac.jobseeker.cv.infrastructure.config;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.GetMyProfessionalProfileService;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.GetMyProfessionalProfileUseCase;
import com.usac.jobseeker.cv.application.usecase.managecertifications.ManageCertificationsService;
import com.usac.jobseeker.cv.application.usecase.managecertifications.ManageCertificationsUseCase;
import com.usac.jobseeker.cv.application.usecase.manageeducation.ManageEducationService;
import com.usac.jobseeker.cv.application.usecase.manageeducation.ManageEducationUseCase;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.ManageExperiencesService;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.ManageExperiencesUseCase;
import com.usac.jobseeker.cv.application.usecase.manageprojects.ManageProjectsService;
import com.usac.jobseeker.cv.application.usecase.manageprojects.ManageProjectsUseCase;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.CandidateCapabilityPort;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.SaveProfessionalProfileService;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.SaveProfessionalProfileUseCase;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcCertificationRepository;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcEducationRepository;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcExperienceRepository;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcProfessionalProfileRepository;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcProfileCollectionsRepository;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcProjectRepository;
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
  JdbcProfileCollectionsRepository jdbcProfileCollectionsRepository(JdbcClient jdbc) {
    return new JdbcProfileCollectionsRepository(jdbc);
  }

  @Bean
  GetMyProfessionalProfileUseCase getMyProfessionalProfileUseCase(
      JdbcProfessionalProfileRepository repository,
      JdbcProfileCollectionsRepository collections,
      UseCaseMetricsPort metrics) {
    return new GetMyProfessionalProfileService(repository, collections, metrics);
  }

  // La bandeja la aporta `app` (ADR-0016) y el reloj de dominio es el del sistema en UTC; los
  // identificadores se generan aquí para que el caso de uso sea determinista en las pruebas.
  @Bean
  SaveProfessionalProfileUseCase saveProfessionalProfileUseCase(
      JdbcProfessionalProfileRepository repository,
      JdbcProfileCollectionsRepository collections,
      CandidateCapabilityPort capabilities,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics) {
    return new SaveProfessionalProfileService(
        repository,
        collections,
        capabilities,
        events,
        metrics,
        Clock.systemUTC(),
        UUID::randomUUID);
  }

  // Lo común a las cuatro colecciones (versión, evento, métrica y log) se construye una vez.
  @Bean
  ProfileChange profileChange(
      JdbcProfessionalProfileRepository repository,
      JdbcProfileCollectionsRepository collections,
      CandidateCapabilityPort capabilities,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics) {
    return new ProfileChange(
        repository,
        collections,
        capabilities,
        events,
        metrics,
        Clock.systemUTC(),
        UUID::randomUUID);
  }

  @Bean
  ManageExperiencesUseCase manageExperiencesUseCase(JdbcClient jdbc, ProfileChange change) {
    return new ManageExperiencesService(new JdbcExperienceRepository(jdbc), change);
  }

  @Bean
  ManageEducationUseCase manageEducationUseCase(JdbcClient jdbc, ProfileChange change) {
    return new ManageEducationService(new JdbcEducationRepository(jdbc), change);
  }

  @Bean
  ManageCertificationsUseCase manageCertificationsUseCase(JdbcClient jdbc, ProfileChange change) {
    return new ManageCertificationsService(new JdbcCertificationRepository(jdbc), change);
  }

  @Bean
  ManageProjectsUseCase manageProjectsUseCase(JdbcClient jdbc, ProfileChange change) {
    return new ManageProjectsService(new JdbcProjectRepository(jdbc), change);
  }
}
