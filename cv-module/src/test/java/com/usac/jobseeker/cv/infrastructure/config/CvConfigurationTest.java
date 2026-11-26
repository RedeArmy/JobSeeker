package com.usac.jobseeker.cv.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.application.storage.MalwareScanPort;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.infrastructure.CvPostgres;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcAvailabilityExclusionRepository;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.JdbcCandidateProfileRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.unit.DataSize;

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
    assertThat(config.getVisibleAvailabilityService(profiles, exclusions)).isNotNull();
  }

  @Test
  void theSnapshotUseCaseIsBuiltFromTheReadAdapters() {
    assertThat(
            config.getProfileSnapshotService(
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

  // Los tres casos de uso de la foto comparten su adaptador y los límites de la configuración.
  @Test
  void thePhotoUseCasesAreBuiltFromTheirAdaptersAndLimits() {
    var properties =
        new CvFileProperties(
            DataSize.ofMegabytes(5),
            20,
            Duration.ofHours(24),
            500,
            10_000,
            500,
            DataSize.ofMegabytes(2),
            512,
            40_000_000);
    var photos = config.jdbcProfilePhotoRepository(jdbc);
    ObjectStoragePort storage =
        (ObjectStoragePort)
            java.lang.reflect.Proxy.newProxyInstance(
                ObjectStoragePort.class.getClassLoader(),
                new Class<?>[] {ObjectStoragePort.class},
                (proxy, method, args) -> {
                  throw new UnsupportedOperationException(method.getName());
                });
    MalwareScanPort scanner = content -> new MalwareScanPort.ScanVerdict.Clean();

    assertThat(
            config.uploadProfilePhotoUseCase(
                photos,
                storage,
                scanner,
                config.imageIoPhotoProcessor(properties),
                properties,
                event -> {},
                (useCase, outcome) -> {}))
        .isNotNull();
    assertThat(config.removeProfilePhotoUseCase(photos, event -> {}, (useCase, outcome) -> {}))
        .isNotNull();
    assertThat(config.getMyProfilePhotoUseCase(photos, storage, (useCase, outcome) -> {}))
        .isNotNull();
  }
}
