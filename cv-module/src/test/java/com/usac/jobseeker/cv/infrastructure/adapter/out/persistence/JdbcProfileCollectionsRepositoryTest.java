package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.Certification;
import com.usac.jobseeker.cv.domain.model.Education;
import com.usac.jobseeker.cv.domain.model.EducationLevel;
import com.usac.jobseeker.cv.domain.model.Experience;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileCollections;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import com.usac.jobseeker.cv.domain.model.Project;
import com.usac.jobseeker.cv.domain.model.ProjectType;
import com.usac.jobseeker.cv.infrastructure.CvPostgres;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Las cuatro colecciones contra las tablas reales de la migración, en Postgres 17: ida y vuelta de
 * cada campo, propiedad del elemento por perfil, orden de lectura y lo que la base rechaza aunque
 * el dominio ya lo haya rechazado antes (la base es la última defensa, no la única).
 */
class JdbcProfileCollectionsRepositoryTest {

  private static final LocalDate JAN = LocalDate.of(2020, 1, 1);
  private static final LocalDate DEC = LocalDate.of(2020, 12, 31);

  private final JdbcClient jdbc = JdbcClient.create(CvPostgres.dataSource());
  private final JdbcProfessionalProfileRepository profiles =
      new JdbcProfessionalProfileRepository(jdbc);
  private final JdbcProfileCollectionsRepository reader =
      new JdbcProfileCollectionsRepository(jdbc);
  private final JdbcExperienceRepository experiences = new JdbcExperienceRepository(jdbc);
  private final JdbcEducationRepository education = new JdbcEducationRepository(jdbc);
  private final JdbcCertificationRepository certifications = new JdbcCertificationRepository(jdbc);
  private final JdbcProjectRepository projects = new JdbcProjectRepository(jdbc);

  private ProfessionalProfileId newProfile() {
    AccountId account = new AccountId(UUID.randomUUID());
    jdbc.sql("insert into accounts (id, is_candidate_enabled) values (:id, true)")
        .param("id", account.value())
        .update();
    var profile =
        ProfessionalProfile.create(
            new ProfessionalProfileId(UUID.randomUUID()),
            account,
            ProfessionalIdentity.of("Ana", null, null),
            Instant.parse("2026-11-23T10:00:00Z"));
    profiles.insertIfAbsent(profile);
    return profile.id();
  }

  private static ProfileItemId id() {
    return new ProfileItemId(UUID.randomUUID());
  }

  // ---- ida y vuelta de cada colección ----

  @Test
  void anExperienceIsReadBackWithEveryFieldAndItsOptionalOnesAsNull() {
    var profile = newProfile();
    var full = new Experience(id(), "Acme", "Dev", JAN, DEC, "Línea 1\nLínea 2 😀");
    var bare = new Experience(id(), "Otra", "Lead", DEC, null, null);

    experiences.insert(profile, full);
    experiences.insert(profile, bare);

    assertThat(reader.findByProfileId(profile).experiences()).containsExactlyInAnyOrder(full, bare);
  }

  @Test
  void aStudyKeepsItsLevelAndAnOptionalLevelComesBackAsNull() {
    var profile = newProfile();
    var withLevel =
        new Education(id(), "USAC", "Ingeniería", EducationLevel.POSTGRAD_SPECIALIZATION, DEC);
    var without = new Education(id(), "INTECAP", "Curso", null, null);

    education.insert(profile, withLevel);
    education.insert(profile, without);

    assertThat(reader.findByProfileId(profile).education())
        .containsExactlyInAnyOrder(withLevel, without);
  }

  @Test
  void everyEducationLevelSurvivesTheRoundTrip() {
    var profile = newProfile();
    for (EducationLevel level : EducationLevel.values()) {
      var study = new Education(id(), "U " + level, "D", level, null);
      education.insert(profile, study);
      assertThat(reader.findByProfileId(profile).education()).contains(study);
    }
  }

  @Test
  void aCertificationIsReadBackWithItsLinkAndItsDates() {
    var profile = newProfile();
    var full = new Certification(id(), "AWS", "Amazon", JAN, DEC, "https://example.test/c/1");
    var bare = new Certification(id(), "Curso", "Academia", null, null, null);

    certifications.insert(profile, full);
    certifications.insert(profile, bare);

    assertThat(reader.findByProfileId(profile).certifications())
        .containsExactlyInAnyOrder(full, bare);
  }

  @Test
  void aProjectKeepsItsTypeAndOptionalFields() {
    var profile = newProfile();
    var work =
        new Project(
            id(), "Sistema", "Desc", ProjectType.WORK, "Líder", JAN, DEC, "https://example.test/p");
    var personal = new Project(id(), "Hobby", null, ProjectType.PERSONAL, null, null, null, null);

    projects.insert(profile, work);
    projects.insert(profile, personal);

    assertThat(reader.findByProfileId(profile).projects())
        .containsExactlyInAnyOrder(work, personal);
  }

  // ---- escritura: reemplazo, baja y recuento ----

  @Test
  void updatingReplacesEveryFieldOfTheItem() {
    var profile = newProfile();
    var itemId = id();
    experiences.insert(profile, new Experience(itemId, "Acme", "Dev", JAN, null, "viejo"));
    var replacement = new Experience(itemId, "Nueva", "Lead", DEC, null, null);

    assertThat(experiences.update(profile, replacement)).isTrue();

    assertThat(reader.findByProfileId(profile).experiences()).containsExactly(replacement);
  }

  @Test
  void deletingRemovesTheItemAndTheCountFollows() {
    var profile = newProfile();
    var one = id();
    var two = id();
    projects.insert(profile, new Project(one, "A", null, ProjectType.WORK, null, null, null, null));
    projects.insert(profile, new Project(two, "B", null, ProjectType.WORK, null, null, null, null));
    assertThat(projects.count(profile)).isEqualTo(2);

    assertThat(projects.delete(profile, one)).isTrue();

    assertThat(projects.count(profile)).isEqualTo(1);
    assertThat(reader.findByProfileId(profile).projects())
        .extracting(Project::id)
        .containsExactly(two);
  }

  @Test
  void updatingOrDeletingAnItemThatDoesNotExistReportsFalseAndChangesNothing() {
    var profile = newProfile();
    certifications.insert(profile, new Certification(id(), "X", "Y", null, null, null));

    assertThat(certifications.update(profile, new Certification(id(), "Z", "W", null, null, null)))
        .isFalse();
    assertThat(certifications.delete(profile, id())).isFalse();
    assertThat(certifications.count(profile)).isEqualTo(1);
  }

  // ---- propiedad: un elemento solo se encuentra desde su perfil ----

  @Test
  void anItemOfAnotherProfileCanNeitherBeChangedNorDeleted() {
    var mine = newProfile();
    var theirs = newProfile();
    var theirId = id();
    experiences.insert(theirs, new Experience(theirId, "Suya", "Dev", JAN, null, null));
    education.insert(theirs, new Education(theirId, "Suya", "D", null, null));

    assertThat(experiences.update(mine, new Experience(theirId, "Robada", "Dev", JAN, null, null)))
        .isFalse();
    assertThat(experiences.delete(mine, theirId)).isFalse();
    assertThat(education.update(mine, new Education(theirId, "Robada", "D", null, null))).isFalse();
    assertThat(education.delete(mine, theirId)).isFalse();

    assertThat(reader.findByProfileId(theirs).experiences())
        .extracting(Experience::company)
        .containsExactly("Suya");
    assertThat(reader.findByProfileId(theirs).education())
        .extracting(Education::institution)
        .containsExactly("Suya");
    assertThat(reader.findByProfileId(mine)).isEqualTo(ProfileCollections.empty());
  }

  @Test
  void theCountsAndTheReadsOfOneProfileNeverIncludeAnother() {
    var a = newProfile();
    var b = newProfile();
    experiences.insert(a, new Experience(id(), "A", "x", JAN, null, null));
    experiences.insert(b, new Experience(id(), "B", "x", JAN, null, null));
    experiences.insert(b, new Experience(id(), "C", "x", JAN, null, null));

    assertThat(experiences.count(a)).isEqualTo(1);
    assertThat(experiences.count(b)).isEqualTo(2);
    assertThat(reader.findByProfileId(a).experiences()).hasSize(1);
  }

  // ---- orden de lectura ----

  @Test
  void theCurrentExperienceComesFirstAndThenTheMostRecent() {
    var profile = newProfile();
    var old =
        new Experience(
            id(), "Vieja", "x", LocalDate.of(2010, 1, 1), LocalDate.of(2012, 1, 1), null);
    var recent =
        new Experience(
            id(), "Reciente", "x", LocalDate.of(2018, 1, 1), LocalDate.of(2020, 1, 1), null);
    var current = new Experience(id(), "Actual", "x", LocalDate.of(2021, 1, 1), null, null);
    experiences.insert(profile, old);
    experiences.insert(profile, recent);
    experiences.insert(profile, current);

    assertThat(reader.findByProfileId(profile).experiences())
        .extracting(Experience::company)
        .containsExactly("Actual", "Reciente", "Vieja");
  }

  @Test
  void aStudyInProgressComesBeforeAFinishedOne() {
    var profile = newProfile();
    education.insert(
        profile, new Education(id(), "Terminada", "D", null, LocalDate.of(2015, 1, 1)));
    education.insert(profile, new Education(id(), "En curso", "D", null, null));

    assertThat(reader.findByProfileId(profile).education())
        .extracting(Education::institution)
        .containsExactly("En curso", "Terminada");
  }

  @Test
  void aProfileWithNothingReadsAsEmpty() {
    assertThat(reader.findByProfileId(newProfile())).isEqualTo(ProfileCollections.empty());
  }

  // ---- lo que la base rechaza aunque el dominio también lo rechace ----

  @Test
  void theDatabaseRefusesAnItemOfAProfileThatDoesNotExist() {
    var orphan = new ProfessionalProfileId(UUID.randomUUID());

    assertThatThrownBy(
            () -> experiences.insert(orphan, new Experience(id(), "A", "B", JAN, null, null)))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void theDatabaseRefusesAnExperienceThatEndsBeforeItStarts() {
    var profile = newProfile();
    var inverted = new Experience(id(), "A", "B", DEC, JAN, null);

    assertThatThrownBy(() -> experiences.insert(profile, inverted))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void theDatabaseRefusesBlankOrOversizedNames() {
    var profile = newProfile();
    var blank = new Experience(id(), "   ", "B", JAN, null, null);
    var tooLong = new Experience(id(), "n".repeat(201), "B", JAN, null, null);

    assertThatThrownBy(() -> experiences.insert(profile, blank))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> experiences.insert(profile, tooLong))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void theDatabaseRefusesALinkThatIsNotAWebAddress() {
    var profile = newProfile();
    var script = new Certification(id(), "N", "O", null, null, "javascript:alert(1)");
    var project =
        new Project(id(), "P", null, ProjectType.WORK, null, null, null, "ftp://example.test");

    assertThatThrownBy(() -> certifications.insert(profile, script))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> projects.insert(profile, project))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void theDatabaseRefusesAnUnknownLevelOrTypeWrittenBehindTheDomain() {
    var profile = newProfile();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "insert into education_entries (id, professional_profile_id, institution, degree, level)"
                            + " values (:id, :p, 'U', 'D', 'PHD')")
                    .param("id", UUID.randomUUID())
                    .param("p", profile.value())
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "insert into projects (id, professional_profile_id, name, type)"
                            + " values (:id, :p, 'P', 'FREELANCE')")
                    .param("id", UUID.randomUUID())
                    .param("p", profile.value())
                    .update())
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void theDatabaseRefusesACertificationThatExpiresBeforeItWasIssued() {
    var profile = newProfile();
    var inverted = new Certification(id(), "N", "O", DEC, JAN, null);

    assertThatThrownBy(() -> certifications.insert(profile, inverted))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aTableNameThatIsNotOneOfTheFourIsRejectedBeforeItReachesTheQuery() {
    var profile = newProfile();

    assertThatThrownBy(
            () -> ProfileItemTables.count(jdbc, "accounts; drop table accounts", profile))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ProfileItemTables.delete(jdbc, "accounts", profile, id()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
