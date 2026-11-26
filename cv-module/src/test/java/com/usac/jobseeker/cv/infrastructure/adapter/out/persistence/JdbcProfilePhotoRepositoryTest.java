package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.application.storage.FilePurpose;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.rules.FileType;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.infrastructure.CvPostgres;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * La foto contra la tabla real (Postgres 17, `tdd/cv-module.md` §10.8): solo el perfil vivo de la
 * cuenta tiene hueco, y la base rechaza una clave o un tipo que la aplicación no crea.
 */
class JdbcProfilePhotoRepositoryTest {

  private static final Instant T0 = Instant.parse("2026-11-26T10:00:00Z");

  private final JdbcClient jdbc = JdbcClient.create(CvPostgres.dataSource());
  private final JdbcProfilePhotoRepository photos = new JdbcProfilePhotoRepository(jdbc);
  private final JdbcCvFileRepository files = new JdbcCvFileRepository(jdbc);

  @Test
  void aPhotoIsSetReadAndCleared() {
    var account = anAccountWithProfile();
    var profile = photos.find(account).orElseThrow();
    assertThat(profile.hasPhoto()).isFalse();
    String key = "photo/" + UUID.randomUUID();

    photos.set(profile.profileId(), key, FileType.JPEG);
    var withPhoto = photos.lock(account).orElseThrow();
    photos.set(profile.profileId(), null, null);

    assertThat(withPhoto.key()).isEqualTo(key);
    assertThat(withPhoto.type()).isEqualTo(FileType.JPEG);
    assertThat(withPhoto.profileId()).isEqualTo(profile.profileId());
    assertThat(photos.find(account).orElseThrow().hasPhoto()).isFalse();
  }

  @Test
  void anAccountWithoutAProfileOrAnAnonymizedOneHasNoSlot() {
    var none = new AccountId(UUID.randomUUID());
    var anonymized = anAccountWithProfile();
    new JdbcAnonymizationRepository(jdbc)
        .anonymizeProfessional(anonymized, "Candidato eliminado", T0.plusSeconds(60));

    assertThat(photos.find(none)).isEmpty();
    assertThat(photos.lock(none)).isEmpty();
    assertThat(photos.find(anonymized)).isEmpty();
  }

  @Test
  void theDatabaseRejectsAForeignKeyAndAKeyWithoutType() {
    var account = anAccountWithProfile();
    var id = photos.find(account).orElseThrow().profileId();

    assertThatThrownBy(() -> photos.set(id, "cv/" + UUID.randomUUID(), FileType.PNG))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> photos.set(id, "photo/" + UUID.randomUUID(), FileType.PDF))
        .isInstanceOf(DataAccessException.class);
  }

  // TD-041: la reconciliación de fotos compara contra la columna de la foto, no contra los CV.
  @Test
  void onlyAPhotoInUseCountsAsAReference() {
    var account = anAccountWithProfile();
    String live = "photo/" + UUID.randomUUID();
    String gone = "photo/" + UUID.randomUUID();
    photos.set(photos.find(account).orElseThrow().profileId(), live, FileType.PNG);

    assertThat(files.referenced(FilePurpose.PHOTO, List.of(live, gone))).containsExactly(live);
    assertThatThrownBy(() -> files.referenced(FilePurpose.ANSWER, List.of(live)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private AccountId anAccountWithProfile() {
    AccountId account = new AccountId(UUID.randomUUID());
    jdbc.sql("insert into accounts (id, is_candidate_enabled) values (:id, true)")
        .param("id", account.value())
        .update();
    new JdbcProfessionalProfileRepository(jdbc)
        .insertIfAbsent(
            new ProfessionalProfile(
                new ProfessionalProfileId(UUID.randomUUID()),
                account,
                ProfessionalIdentity.of("Ana", null, null),
                1,
                T0));
    return account;
  }
}
