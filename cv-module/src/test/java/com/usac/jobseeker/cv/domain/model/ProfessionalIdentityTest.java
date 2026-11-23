package com.usac.jobseeker.cv.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfessionalIdentityTest {

  private static List<FieldViolation> violationsOf(String name, String headline, String bio) {
    return org.junit.jupiter.api.Assertions.assertThrows(
            ValidationFailedException.class, () -> ProfessionalIdentity.of(name, headline, bio))
        .violations();
  }

  @Test
  void aValidIdentityKeepsItsTrimmedValues() {
    var identity = ProfessionalIdentity.of("  Ana López  ", " Backend Engineer ", " Hola\nmundo ");

    assertThat(identity.fullName()).isEqualTo("Ana López");
    assertThat(identity.headline()).isEqualTo("Backend Engineer");
    assertThat(identity.bio()).isEqualTo("Hola\nmundo");
  }

  @Test
  void theHeadlineAndTheBioAreOptionalAndABlankOneIsTheSameAsNone() {
    var identity = ProfessionalIdentity.of("Ana", "   ", "");

    assertThat(identity.headline()).isNull();
    assertThat(identity.bio()).isNull();
    assertThat(ProfessionalIdentity.of("Ana", null, null).headline()).isNull();
  }

  @Test
  void theNameIsRequired() {
    assertThat(violationsOf(null, null, null))
        .containsExactly(new FieldViolation("fullName", "REQUIRED"));
    assertThat(violationsOf("   ", null, null))
        .containsExactly(new FieldViolation("fullName", "REQUIRED"));
  }

  @Test
  void everyViolationIsReportedAtOnceAndNeverTheValue() {
    var violations = violationsOf("", "x".repeat(201), "y".repeat(4001));

    assertThat(violations)
        .containsExactlyInAnyOrder(
            new FieldViolation("fullName", "REQUIRED"),
            new FieldViolation("headline", "TOO_LONG"),
            new FieldViolation("bio", "TOO_LONG"));
  }

  @Test
  void theLimitsAreInclusive() {
    var identity = ProfessionalIdentity.of("n".repeat(200), "h".repeat(200), "b".repeat(4000));

    assertThat(identity.fullName()).hasSize(200);
    assertThat(identity.headline()).hasSize(200);
    assertThat(identity.bio()).hasSize(4000);
    assertThat(violationsOf("n".repeat(201), null, null))
        .containsExactly(new FieldViolation("fullName", "TOO_LONG"));
  }

  @Test
  void aLengthIsCountedInCharactersAndNotInUtf16Units() {
    // Un emoji ocupa 2 unidades UTF-16 pero es 1 carácter: 200 de ellos caben, 201 no.
    assertThat(ProfessionalIdentity.of("😀".repeat(200), null, null).fullName()).isNotNull();
    assertThat(violationsOf("😀".repeat(201), null, null))
        .containsExactly(new FieldViolation("fullName", "TOO_LONG"));
  }

  @Test
  void aSingleLineFieldRejectsAnyControlCharacterIncludingNewlines() {
    assertThat(violationsOf("Ana\nLópez", null, null))
        .containsExactly(new FieldViolation("fullName", "INVALID_CHARACTERS"));
    assertThat(violationsOf("Ana", "a\tb", null))
        .containsExactly(new FieldViolation("headline", "INVALID_CHARACTERS"));
  }

  @Test
  void theNulCharacterIsRejectedBecausePostgresCannotStoreIt() {
    assertThat(violationsOf("Ana\u0000", null, null))
        .containsExactly(new FieldViolation("fullName", "INVALID_CHARACTERS"));
    assertThat(violationsOf("Ana", null, "hola\u0000"))
        .containsExactly(new FieldViolation("bio", "INVALID_CHARACTERS"));
  }

  @Test
  void theBioKeepsItsLineBreaksAndTabsButNotOtherControlCharacters() {
    assertThat(ProfessionalIdentity.of("Ana", null, "uno\r\ndos\ttres").bio())
        .isEqualTo("uno\r\ndos\ttres");
    assertThatThrownBy(() -> ProfessionalIdentity.of("Ana", null, "a\u0007b"))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void theMessageOfTheErrorNeverContainsTheRejectedValue() {
    assertThatThrownBy(() -> ProfessionalIdentity.of("secreto\u0000", null, null))
        .hasMessageNotContaining("secreto");
  }
}
