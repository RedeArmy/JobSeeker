package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.rules.FileType;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.CvFile;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.infrastructure.CvPostgres;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Las versiones del CV contra la tabla real (Postgres 17, `tdd/cv-module.md` §10.7): una sola
 * vigente por perfil, el historial ordenado, el aislamiento entre perfiles y el borrado al
 * anonimizar, que la base impone además del código.
 */
class JdbcCvFileRepositoryTest {

  private static final Instant T0 = Instant.parse("2026-11-26T10:00:00Z");

  private final JdbcClient jdbc = JdbcClient.create(CvPostgres.dataSource());
  private final JdbcCvFileRepository files = new JdbcCvFileRepository(jdbc);
  private final JdbcAnonymizationRepository anonymization = new JdbcAnonymizationRepository(jdbc);

  @Test
  void aNewVersionBecomesTheOnlyCurrentOneAndTheHistoryIsNewestFirst() {
    var profile = aCandidate();
    var first = cv(profile, T0, true);
    var second = cv(profile, T0.plusSeconds(60), true);

    files.insertCurrent(first);
    files.insertCurrent(second);

    assertThat(files.current(profile))
        .hasValueSatisfying(c -> assertThat(c.id()).isEqualTo(second.id()));
    assertThat(files.versions(profile))
        .extracting(CvFile::id)
        .containsExactly(second.id(), first.id());
    assertThat(files.find(profile, first.id()))
        .hasValueSatisfying(f -> assertThat(f.current()).isFalse());
    assertThat(files.count(profile)).isEqualTo(2);
  }

  // Una versión de otro perfil no existe para este: el aislamiento lo da la consulta, no el caller.
  @Test
  void aVersionOfAnotherProfileIsNotFound() {
    var mine = aCandidate();
    var theirs = aCandidate();
    var theirFile = cv(theirs, T0, true);
    files.insertCurrent(theirFile);

    assertThat(files.find(mine, theirFile.id())).isEmpty();
    assertThat(files.versions(mine)).isEmpty();
    assertThat(files.current(mine)).isEmpty();
  }

  // La base garantiza «una sola vigente», aunque el código fallara.
  @Test
  void theDatabaseRejectsASecondCurrentVersion() {
    var profile = aCandidate();
    files.insertCurrent(cv(profile, T0, true));

    var duplicate = cv(profile, T0.plusSeconds(1), true);
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "insert into candidate_cv_files (id, candidate_profile_id, object_key,"
                            + " display_name, file_type, size_bytes, sha256, is_current, uploaded_at)"
                            + " values (:id, :p, :k, 'x.pdf', 'PDF', 1, :sha, true, now())")
                    .param("id", duplicate.id())
                    .param("p", profile.value())
                    .param("k", duplicate.objectKey())
                    .param("sha", duplicate.sha256())
                    .update())
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void lockingAnExistingProfileSucceedsInsideATransaction() {
    var profile = aCandidate();

    files.lockProfile(profile);

    assertThat(files.count(profile)).isZero();
  }

  // BR-DATA-05 y ADR-0021 §6: se devuelven las claves y la fila queda sin archivo, sin nombre y sin
  // vigente; ninguna lectura vuelve a mostrarla.
  @Test
  void anonymizationDiscardsEveryVersionAndReturnsTheirKeys() {
    var profile = aCandidate();
    var first = cv(profile, T0, true);
    var second = cv(profile, T0.plusSeconds(60), true);
    files.insertCurrent(first);
    files.insertCurrent(second);

    var keys = anonymization.discardCvFiles(profile.value(), T0.plusSeconds(3600));

    assertThat(keys).containsExactlyInAnyOrder(first.objectKey(), second.objectKey());
    assertThat(files.versions(profile)).isEmpty();
    assertThat(files.current(profile)).isEmpty();
    assertThat(files.count(profile)).isZero();
    var leftover =
        jdbc.sql(
                "select count(*) from candidate_cv_files where candidate_profile_id = :p"
                    + " and (object_key is not null or display_name is not null or is_current)")
            .param("p", profile.value())
            .query(Integer.class)
            .single();
    assertThat(leftover).isZero();
    assertThat(anonymization.discardCvFiles(profile.value(), T0.plusSeconds(7200))).isEmpty();
  }

  @Test
  void theDatabaseRejectsADeletedVersionThatKeepsItsFile() {
    var profile = aCandidate();
    var file = cv(profile, T0, true);
    files.insertCurrent(file);

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "update candidate_cv_files set deleted_at = now(), is_current = false"
                            + " where id = :id")
                    .param("id", file.id())
                    .update())
        .isInstanceOf(DataAccessException.class);
  }

  private CandidateProfileId aCandidate() {
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
    var candidate =
        CandidateProfile.create(
            new CandidateProfileId(UUID.randomUUID()),
            account,
            CandidateDetails.of("a@b.co", null, null, null, null, null, null, null, null, null),
            T0);
    new JdbcCandidateProfileRepository(jdbc).insertIfAbsent(candidate);
    return candidate.id();
  }

  private static CvFile cv(CandidateProfileId profile, Instant at, boolean current) {
    byte[] hash = new byte[32];
    new java.util.Random().nextBytes(hash);
    return new CvFile(
        UUID.randomUUID(),
        profile,
        "cv/" + UUID.randomUUID(),
        "Mi CV.pdf",
        FileType.PDF,
        1234,
        HexFormat.of().formatHex(hash),
        current,
        at);
  }
}
