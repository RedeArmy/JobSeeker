package com.usac.jobseeker.common.domain.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ErrorsTest {

  @Test
  void aViolationNeedsAFieldAndACode() {
    assertThatThrownBy(() -> new FieldViolation(" ", "REQUIRED"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FieldViolation("fullName", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FieldViolation(null, "REQUIRED"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new FieldViolation("fullName", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void theExceptionKeepsEveryViolationAndNamesOnlyTheFields() {
    var exception =
        new ValidationFailedException(
            List.of(
                new FieldViolation("fullName", "REQUIRED"), new FieldViolation("bio", "TOO_LONG")));

    assertThat(exception.violations())
        .containsExactly(
            new FieldViolation("fullName", "REQUIRED"), new FieldViolation("bio", "TOO_LONG"));
    assertThat(exception.getMessage()).isEqualTo("Datos no válidos en: fullName, bio");
  }

  @Test
  void theViolationsCannotBeChangedAfterwards() {
    var source = new java.util.ArrayList<>(List.of(new FieldViolation("fullName", "REQUIRED")));
    var exception = new ValidationFailedException(source);
    source.add(new FieldViolation("bio", "TOO_LONG"));

    var violations = exception.violations();
    var extra = new FieldViolation("a", "B");
    assertThat(violations).hasSize(1);
    assertThatThrownBy(() -> violations.add(extra))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void anExceptionWithoutViolationsMakesNoSense() {
    List<FieldViolation> none = List.of();

    assertThatThrownBy(() -> new ValidationFailedException(none))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aMissingCapabilityNamesNeitherTheCapabilityNorTheResource() {
    assertThat(new ForbiddenCapabilityException().getMessage())
        .isEqualTo("La cuenta no tiene la capacidad necesaria");
  }

  @Test
  void aNotFoundCarriesNoIdentifierOfTheResource() {
    assertThat(new NotFoundException().getMessage()).isEqualTo("El recurso no existe");
  }

  @Test
  void theStaleStateCarriesNoDataOfTheResource() {
    assertThat(new StaleStateException().getMessage())
        .isEqualTo("El recurso cambió desde que se leyó");
  }
}
