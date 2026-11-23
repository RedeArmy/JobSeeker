package com.usac.jobseeker.cv.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Las reglas de los cuatro elementos del perfil: todo error a la vez y nunca el valor rechazado.
 */
class ProfileItemsTest {

  private static final ProfileItemId ID = new ProfileItemId(UUID.randomUUID());
  private static final LocalDate JAN = LocalDate.of(2020, 1, 1);
  private static final LocalDate DEC = LocalDate.of(2020, 12, 31);

  private static List<FieldViolation> violationsOf(Supplier<?> construction) {
    return org.junit.jupiter.api.Assertions.assertThrows(
            ValidationFailedException.class, construction::get)
        .violations();
  }

  // ---- experiencia ----

  @Test
  void anExperienceKeepsItsTrimmedValuesAndIsCurrentWithoutAnEndDate() {
    var experience = Experience.of(ID, "  Acme  ", " Backend ", JAN, null, " Hice cosas ");

    assertThat(experience.company()).isEqualTo("Acme");
    assertThat(experience.title()).isEqualTo("Backend");
    assertThat(experience.description()).isEqualTo("Hice cosas");
    assertThat(experience.isCurrent()).isTrue();
    assertThat(Experience.of(ID, "A", "B", JAN, DEC, null).isCurrent()).isFalse();
  }

  @Test
  void anExperienceReportsEveryProblemAtOnce() {
    var violations =
        violationsOf(() -> Experience.of(ID, " ", "t".repeat(201), null, null, "d".repeat(4001)));

    assertThat(violations)
        .containsExactlyInAnyOrder(
            new FieldViolation("company", "REQUIRED"),
            new FieldViolation("title", "TOO_LONG"),
            new FieldViolation("startDate", "REQUIRED"),
            new FieldViolation("description", "TOO_LONG"));
  }

  @Test
  void anExperienceCannotEndBeforeItStartsButMayEndTheSameDay() {
    assertThat(violationsOf(() -> Experience.of(ID, "A", "B", DEC, JAN, null)))
        .containsExactly(new FieldViolation("endDate", "END_BEFORE_START"));
    assertThat(Experience.of(ID, "A", "B", JAN, JAN, null).endDate()).isEqualTo(JAN);
  }

  @Test
  void aMistypedYearIsOutOfRange() {
    assertThat(violationsOf(() -> Experience.of(ID, "A", "B", LocalDate.of(202, 1, 1), null, null)))
        .containsExactly(new FieldViolation("startDate", "OUT_OF_RANGE"));
    assertThat(
            violationsOf(() -> Experience.of(ID, "A", "B", JAN, LocalDate.of(20255, 1, 1), null)))
        .containsExactly(new FieldViolation("endDate", "OUT_OF_RANGE"));
  }

  @Test
  void theLimitDatesAreInclusive() {
    assertThat(
            Experience.of(ID, "A", "B", LocalDate.of(1900, 1, 1), LocalDate.of(2100, 12, 31), null))
        .isNotNull();
  }

  @Test
  void anExperienceRejectsControlCharactersButAllowsLineBreaksInTheDescription() {
    assertThat(violationsOf(() -> Experience.of(ID, "Ac\u0000me", "B", JAN, null, null)))
        .containsExactly(new FieldViolation("company", "INVALID_CHARACTERS"));
    assertThat(violationsOf(() -> Experience.of(ID, "Acme\nSA", "B", JAN, null, null)))
        .containsExactly(new FieldViolation("company", "INVALID_CHARACTERS"));
    assertThat(Experience.of(ID, "A", "B", JAN, null, "uno\ndos").description())
        .isEqualTo("uno\ndos");
  }

  @Test
  void theMessageNeverContainsTheRejectedValue() {
    assertThatThrownBy(() -> Experience.of(ID, "secreto\u0000", "B", JAN, null, null))
        .hasMessageNotContaining("secreto");
  }

  // ---- formación ----

  @Test
  void aStudyIsInProgressWithoutACompletionDateAndTheLevelIsOptional() {
    var study = Education.of(ID, " USAC ", " Ingeniería ", null, null);

    assertThat(study.institution()).isEqualTo("USAC");
    assertThat(study.level()).isNull();
    assertThat(study.isInProgress()).isTrue();
    assertThat(Education.of(ID, "USAC", "Ing", EducationLevel.BACHELOR, DEC).isInProgress())
        .isFalse();
  }

  @Test
  void aStudyNeedsInstitutionAndDegree() {
    assertThat(violationsOf(() -> Education.of(ID, "", null, null, null)))
        .containsExactlyInAnyOrder(
            new FieldViolation("institution", "REQUIRED"),
            new FieldViolation("degree", "REQUIRED"));
  }

  @Test
  void theLevelsAreOrderedFromLowestToHighestBecauseEligibilityComparesThem() {
    assertThat(EducationLevel.values())
        .containsExactly(
            EducationLevel.SECONDARY,
            EducationLevel.TECHNICAL,
            EducationLevel.BACHELOR,
            EducationLevel.POSTGRAD_SPECIALIZATION,
            EducationLevel.MASTERS,
            EducationLevel.DOCTORATE);
    assertThat(EducationLevel.MASTERS).isGreaterThan(EducationLevel.BACHELOR);
  }

  @Test
  void aCompletionDateOutOfRangeIsRejected() {
    assertThat(violationsOf(() -> Education.of(ID, "U", "D", null, LocalDate.of(1800, 1, 1))))
        .containsExactly(new FieldViolation("completionDate", "OUT_OF_RANGE"));
  }

  // ---- certificación ----

  @Test
  void aCertificationNeverExpiresWithoutAnExpirationDate() {
    var certification =
        Certification.of(ID, " AWS SAA ", " Amazon ", JAN, null, " https://example.test/c/1 ");

    assertThat(certification.name()).isEqualTo("AWS SAA");
    assertThat(certification.credentialUrl()).isEqualTo("https://example.test/c/1");
    assertThat(certification.expirationDate()).isNull();
  }

  @Test
  void aCertificationCannotExpireBeforeItWasIssued() {
    assertThat(violationsOf(() -> Certification.of(ID, "N", "O", DEC, JAN, null)))
        .containsExactly(new FieldViolation("expirationDate", "END_BEFORE_START"));
  }

  @Test
  void aCertificationNeedsNameAndIssuer() {
    assertThat(violationsOf(() -> Certification.of(ID, null, " ", null, null, null)))
        .containsExactlyInAnyOrder(
            new FieldViolation("name", "REQUIRED"),
            new FieldViolation("issuingOrganization", "REQUIRED"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "javascript:alert(1)",
        "data:text/html,x",
        "ftp://example.test/x",
        "//example.test/x",
        "example.test",
        "https://",
        "https:///x",
        "https://exa mple.test",
        "http://example.test/\nx",
        "file:///etc/passwd"
      })
  void aLinkThatIsNotAWebAddressIsRejected(String url) {
    assertThat(violationsOf(() -> Certification.of(ID, "N", "O", null, null, url)))
        .containsExactly(new FieldViolation("credentialUrl", "INVALID_URL"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://example.test",
        "https://example.test/a/b?x=1#y",
        "HTTPS://EXAMPLE.TEST/A",
        "https://example.test:8443/path",
        "https://usuario.example.test/perfil"
      })
  void aWebAddressIsAccepted(String url) {
    assertThat(Certification.of(ID, "N", "O", null, null, url).credentialUrl()).isEqualTo(url);
  }

  @Test
  void aLinkTooLongIsRejectedAsLongAndNotAsInvalid() {
    String longUrl = "https://example.test/" + "a".repeat(2000);

    assertThat(violationsOf(() -> Certification.of(ID, "N", "O", null, null, longUrl)))
        .containsExactly(new FieldViolation("credentialUrl", "TOO_LONG"));
  }

  // ---- proyecto ----

  @Test
  void aProjectKeepsItsTypeAndIsOngoingWithoutAnEndDate() {
    var project =
        Project.of(
            ID,
            " JobSeeker ",
            " Sistema ",
            ProjectType.PERSONAL,
            " Autor ",
            JAN,
            null,
            "https://example.test/p");

    assertThat(project.name()).isEqualTo("JobSeeker");
    assertThat(project.type()).isEqualTo(ProjectType.PERSONAL);
    assertThat(project.role()).isEqualTo("Autor");
    assertThat(project.endDate()).isNull();
  }

  @Test
  void aProjectNeedsANameAndAType() {
    assertThat(violationsOf(() -> Project.of(ID, "", null, null, null, null, null, null)))
        .containsExactlyInAnyOrder(
            new FieldViolation("name", "REQUIRED"), new FieldViolation("type", "REQUIRED"));
  }

  @Test
  void aProjectWithBothDatesRespectsTheirOrderButEitherMayBeMissing() {
    assertThat(
            violationsOf(() -> Project.of(ID, "P", null, ProjectType.WORK, null, DEC, JAN, null)))
        .containsExactly(new FieldViolation("endDate", "END_BEFORE_START"));
    assertThat(Project.of(ID, "P", null, ProjectType.WORK, null, null, JAN, null).startDate())
        .isNull();
    assertThat(Project.of(ID, "P", null, ProjectType.WORK, null, JAN, null, null).endDate())
        .isNull();
  }

  @Test
  void aProjectLinkFollowsTheSameRule() {
    assertThat(
            violationsOf(
                () ->
                    Project.of(ID, "P", null, ProjectType.WORK, null, null, null, "javascript:x")))
        .containsExactly(new FieldViolation("url", "INVALID_URL"));
  }

  // ---- el conjunto ----

  @Test
  void theCollectionsAreImmutableCopiesAndTheEmptyOneHasNoElements() {
    var source = new java.util.ArrayList<>(List.of(Experience.of(ID, "A", "B", JAN, null, null)));
    var collections = new ProfileCollections(source, List.of(), List.of(), List.of());
    source.clear();

    assertThat(collections.experiences()).hasSize(1);
    var stored = collections.experiences();
    assertThatThrownBy(stored::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThat(ProfileCollections.empty().experiences()).isEmpty();
    assertThat(ProfileCollections.MAX_PER_COLLECTION).isEqualTo(50);
  }

  @Test
  void anItemIdNeedsAValue() {
    assertThatThrownBy(() -> new ProfileItemId(null)).isInstanceOf(NullPointerException.class);
    assertThat(new ProfileItemId(ID.value())).hasToString(ID.value().toString());
  }

  @Test
  void touchingAProfileAdvancesTheVersionAndKeepsTheIdentity() {
    var profile =
        ProfessionalProfile.create(
            new ProfessionalProfileId(UUID.randomUUID()),
            new com.usac.jobseeker.common.domain.model.AccountId(UUID.randomUUID()),
            ProfessionalIdentity.of("Ana", null, null),
            java.time.Instant.parse("2026-11-23T10:00:00Z"));

    var touched = profile.touched(java.time.Instant.parse("2026-11-23T11:00:00Z"));

    assertThat(touched.version()).isEqualTo(2);
    assertThat(touched.identity()).isEqualTo(profile.identity());
    assertThat(touched.updatedAt()).isEqualTo(java.time.Instant.parse("2026-11-23T11:00:00Z"));
    assertThat(profile.version()).isEqualTo(1);
  }
}
