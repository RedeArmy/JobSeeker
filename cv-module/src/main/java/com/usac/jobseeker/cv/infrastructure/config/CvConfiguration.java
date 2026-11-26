package com.usac.jobseeker.cv.infrastructure.config;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import com.usac.jobseeker.cv.application.support.Stamp;
import com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata.AnonymizeProfileDataService;
import com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata.AnonymizeProfileDataUseCase;
import com.usac.jobseeker.cv.application.usecase.changeavailability.ChangeAvailabilityService;
import com.usac.jobseeker.cv.application.usecase.changeavailability.ChangeAvailabilityUseCase;
import com.usac.jobseeker.cv.application.usecase.changediscoverability.ChangeDiscoverabilityService;
import com.usac.jobseeker.cv.application.usecase.changediscoverability.ChangeDiscoverabilityUseCase;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.GetMyCandidateProfileService;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.GetMyCandidateProfileUseCase;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.GetMyProfessionalProfileService;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.GetMyProfessionalProfileUseCase;
import com.usac.jobseeker.cv.application.usecase.getprofilesnapshot.GetProfileSnapshotService;
import com.usac.jobseeker.cv.application.usecase.getvisibleavailability.GetVisibleAvailabilityService;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.AccountDirectoryPort;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.ManageAvailabilityExclusionsService;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.ManageAvailabilityExclusionsUseCase;
import com.usac.jobseeker.cv.application.usecase.managecertifications.ManageCertificationsService;
import com.usac.jobseeker.cv.application.usecase.managecertifications.ManageCertificationsUseCase;
import com.usac.jobseeker.cv.application.usecase.manageeducation.ManageEducationService;
import com.usac.jobseeker.cv.application.usecase.manageeducation.ManageEducationUseCase;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.ManageExperiencesService;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.ManageExperiencesUseCase;
import com.usac.jobseeker.cv.application.usecase.manageprojects.ManageProjectsService;
import com.usac.jobseeker.cv.application.usecase.manageprojects.ManageProjectsUseCase;
import com.usac.jobseeker.cv.application.usecase.replacecandidatelanguages.ReplaceCandidateLanguagesService;
import com.usac.jobseeker.cv.application.usecase.replacecandidatelanguages.ReplaceCandidateLanguagesUseCase;
import com.usac.jobseeker.cv.application.usecase.replacecandidateskills.ReplaceCandidateSkillsService;
import com.usac.jobseeker.cv.application.usecase.replacecandidateskills.ReplaceCandidateSkillsUseCase;
import com.usac.jobseeker.cv.application.usecase.replaceworkauthorizations.ReplaceWorkAuthorizationsService;
import com.usac.jobseeker.cv.application.usecase.replaceworkauthorizations.ReplaceWorkAuthorizationsUseCase;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.SaveCandidateProfileService;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.SaveCandidateProfileUseCase;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.CandidateCapabilityPort;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.SaveProfessionalProfileService;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.SaveProfessionalProfileUseCase;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcAnonymizationRepository;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcAvailabilityExclusionRepository;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcCandidateCollectionsRepository;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcCandidateProfileRepository;
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

  // ---- perfil de candidato (sub-paso 3 del diseño de cv) ----

  @Bean
  JdbcCandidateProfileRepository jdbcCandidateProfileRepository(JdbcClient jdbc) {
    return new JdbcCandidateProfileRepository(jdbc);
  }

  @Bean
  JdbcCandidateCollectionsRepository jdbcCandidateCollectionsRepository(JdbcClient jdbc) {
    return new JdbcCandidateCollectionsRepository(jdbc);
  }

  @Bean
  GetMyCandidateProfileUseCase getMyCandidateProfileUseCase(
      JdbcCandidateProfileRepository profiles,
      JdbcCandidateCollectionsRepository collections,
      CandidateCapabilityPort capabilities,
      UseCaseMetricsPort metrics) {
    return new GetMyCandidateProfileService(profiles, collections, capabilities, metrics);
  }

  @Bean
  SaveCandidateProfileUseCase saveCandidateProfileUseCase(
      JdbcCandidateProfileRepository profiles,
      JdbcCandidateCollectionsRepository collections,
      JdbcProfessionalProfileRepository professionalProfiles,
      CandidateCapabilityPort capabilities,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics) {
    return new SaveCandidateProfileService(
        profiles, collections, professionalProfiles, capabilities, events, metrics, Stamp.system());
  }

  // Lo común a los tres conjuntos (capacidad, versión, evento, métrica y log) se construye una vez.
  @Bean
  CandidateChange candidateChange(
      JdbcCandidateProfileRepository profiles,
      JdbcCandidateCollectionsRepository collections,
      CandidateCapabilityPort capabilities,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics) {
    return new CandidateChange(
        profiles, collections, capabilities, events, metrics, Clock.systemUTC(), UUID::randomUUID);
  }

  @Bean
  ReplaceCandidateSkillsUseCase replaceCandidateSkillsUseCase(
      JdbcCandidateCollectionsRepository store, CandidateChange change) {
    return new ReplaceCandidateSkillsService(store, change);
  }

  @Bean
  ReplaceCandidateLanguagesUseCase replaceCandidateLanguagesUseCase(
      JdbcCandidateCollectionsRepository store, CandidateChange change) {
    return new ReplaceCandidateLanguagesService(store, change);
  }

  @Bean
  ReplaceWorkAuthorizationsUseCase replaceWorkAuthorizationsUseCase(
      JdbcCandidateCollectionsRepository store, CandidateChange change) {
    return new ReplaceWorkAuthorizationsService(store, change);
  }

  // ---- descubribilidad, disponibilidad y lista de exclusión (sub-paso 4 del diseño de cv) ----

  @Bean
  JdbcAvailabilityExclusionRepository jdbcAvailabilityExclusionRepository(JdbcClient jdbc) {
    return new JdbcAvailabilityExclusionRepository(jdbc);
  }

  @Bean
  ChangeDiscoverabilityUseCase changeDiscoverabilityUseCase(
      JdbcCandidateProfileRepository store, CandidateChange change) {
    return new ChangeDiscoverabilityService(store, change);
  }

  @Bean
  ChangeAvailabilityUseCase changeAvailabilityUseCase(
      JdbcCandidateProfileRepository store, CandidateChange change) {
    return new ChangeAvailabilityService(store, change);
  }

  @Bean
  ManageAvailabilityExclusionsUseCase manageAvailabilityExclusionsUseCase(
      JdbcAvailabilityExclusionRepository exclusions,
      AccountDirectoryPort directory,
      CandidateChange change,
      UseCaseMetricsPort metrics) {
    return new ManageAvailabilityExclusionsService(
        exclusions, directory, change, Clock.systemUTC(), metrics);
  }

  // Un solo bean con las dos interfaces (individual y por lotes): dos instancias del mismo servicio
  // darían dos candidatos para cada tipo (TD-039).
  @Bean
  GetVisibleAvailabilityService getVisibleAvailabilityService(
      JdbcCandidateProfileRepository profiles, JdbcAvailabilityExclusionRepository exclusions) {
    return new GetVisibleAvailabilityService(profiles, exclusions);
  }

  // ---- lectura para otros módulos (sub-paso 5 del diseño de cv) ----

  // Un solo bean con las dos interfaces (individual y por lotes), por la misma razón (TD-039).
  @Bean
  GetProfileSnapshotService getProfileSnapshotService(
      CandidateCapabilityPort capabilities,
      JdbcProfessionalProfileRepository professionals,
      JdbcProfileCollectionsRepository professionalCollections,
      JdbcCandidateProfileRepository candidates,
      JdbcCandidateCollectionsRepository candidateCollections,
      UseCaseMetricsPort metrics) {
    return new GetProfileSnapshotService(
        capabilities,
        professionals,
        professionalCollections,
        candidates,
        candidateCollections,
        metrics);
  }

  // ---- anonimización (sub-paso 6 del diseño de cv) ----

  @Bean
  JdbcAnonymizationRepository jdbcAnonymizationRepository(JdbcClient jdbc) {
    return new JdbcAnonymizationRepository(jdbc);
  }

  @Bean
  AnonymizeProfileDataUseCase anonymizeProfileDataUseCase(
      JdbcAnonymizationRepository store,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics) {
    return new AnonymizeProfileDataService(store, events, metrics, Stamp.system());
  }
}
