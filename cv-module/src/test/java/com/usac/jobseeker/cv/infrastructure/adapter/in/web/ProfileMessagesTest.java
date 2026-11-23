package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.cv.domain.model.Certification;
import com.usac.jobseeker.cv.domain.model.Education;
import com.usac.jobseeker.cv.domain.model.Experience;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import com.usac.jobseeker.cv.domain.model.Project;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ProfileMessagesTest {

  private static final ProfileItemId ID = new ProfileItemId(UUID.randomUUID());

  @Test
  void aViolationReadsAsAFieldAndItsRuleInSpanish() {
    assertThat(
            ProfileMessages.describe(
                List.of(
                    new FieldViolation("company", "REQUIRED"),
                    new FieldViolation("endDate", "END_BEFORE_START"),
                    new FieldViolation("projects", "TOO_MANY"))))
        .containsExactly(
            "La empresa es obligatorio.",
            "La fecha de fin no puede ser anterior a la fecha de inicio.",
            "Los proyectos ya alcanzó el máximo de 50 elementos.");
  }

  @Test
  void anUnknownFieldOrRuleStillProducesAReadableMessageAndNeverFailsTheScreen() {
    assertThat(ProfileMessages.describe(List.of(new FieldViolation("campoNuevo", "REGLA_NUEVA"))))
        .containsExactly("Un dato no es válido.");
  }

  @Test
  void theMessageNeverContainsAnythingButTheFieldAndTheRule() {
    var messages = ProfileMessages.describe(List.of(new FieldViolation("fullName", "TOO_LONG")));

    assertThat(messages).singleElement().isEqualTo("El nombre es demasiado largo.");
  }

  /**
   * Cada campo que el dominio puede rechazar debe tener su traducción: si se añade uno y se olvida
   * aquí, esta prueba falla.
   */
  @Test
  void everyFieldTheDomainCanRejectHasATranslation() {
    List<FieldViolation> all = new ArrayList<>();
    String longText = "x".repeat(5000);
    LocalDate far = LocalDate.of(1800, 1, 1);
    for (Supplier<?> invalid :
        List.<Supplier<?>>of(
            () -> ProfessionalIdentity.of("", longText, longText),
            () -> Experience.of(ID, "", "", null, far, longText),
            () -> Education.of(ID, "", "", null, far),
            () -> Certification.of(ID, "", "", far, far, "javascript:x"),
            () -> Project.of(ID, "", longText, null, longText, far, far, "javascript:x"))) {
      try {
        invalid.get();
      } catch (ValidationFailedException e) {
        all.addAll(e.violations());
      }
    }

    assertThat(all).hasSizeGreaterThan(15);
    for (String message : ProfileMessages.describe(all)) {
      assertThat(message).doesNotStartWith("Un dato");
    }
  }
}
