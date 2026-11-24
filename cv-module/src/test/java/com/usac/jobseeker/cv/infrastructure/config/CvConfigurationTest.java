package com.usac.jobseeker.cv.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.infrastructure.CvPostgres;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcAvailabilityExclusionRepository;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcCandidateProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** El cableado de los casos de uso de visibilidad se construye con sus puertos reales. */
class CvConfigurationTest {

  private final CvConfiguration config = new CvConfiguration();
  private final JdbcClient jdbc = JdbcClient.create(CvPostgres.dataSource());

  @Test
  void theVisibilityUseCasesAreBuiltFromTheirAdapters() {
    JdbcCandidateProfileRepository profiles = config.jdbcCandidateProfileRepository(jdbc);
    JdbcAvailabilityExclusionRepository exclusions =
        config.jdbcAvailabilityExclusionRepository(jdbc);
    CandidateChange change =
        config.candidateChange(
            profiles,
            config.jdbcCandidateCollectionsRepository(jdbc),
            account -> true,
            event -> {},
            (useCase, outcome) -> {});

    assertThat(config.changeDiscoverabilityUseCase(profiles, change)).isNotNull();
    assertThat(config.changeAvailabilityUseCase(profiles, change)).isNotNull();
    assertThat(
            config.manageAvailabilityExclusionsUseCase(
                exclusions, account -> true, change, (useCase, outcome) -> {}))
        .isNotNull();
    assertThat(config.getVisibleAvailabilityUseCase(profiles, exclusions)).isNotNull();
  }

  @Test
  void theSnapshotUseCaseIsBuiltFromTheReadAdapters() {
    assertThat(
            config.getProfileSnapshotUseCase(
                account -> true,
                config.jdbcProfessionalProfileRepository(jdbc),
                config.jdbcProfileCollectionsRepository(jdbc),
                config.jdbcCandidateProfileRepository(jdbc),
                config.jdbcCandidateCollectionsRepository(jdbc),
                (useCase, outcome) -> {}))
        .isNotNull();
  }

  @Test
  void theAnonymizationUseCaseIsBuiltFromItsAdapter() {
    assertThat(
            config.anonymizeProfileDataUseCase(
                config.jdbcAnonymizationRepository(jdbc), event -> {}, (useCase, outcome) -> {}))
        .isNotNull();
  }
}
