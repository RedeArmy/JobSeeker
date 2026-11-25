package com.usac.jobseeker.job.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Lo que toda ruta de la vacante hace con su entrada antes de llamar a un caso de uso. */
class WebInputsTest {

  @Test
  void theCallerIsTheSubjectOfTheTokenAndNothingElse() {
    UUID subject = UUID.randomUUID();

    assertThat(WebInputs.caller(subject::toString).value()).isEqualTo(subject);
  }

  @Test
  void aVersionIsRequiredOnEveryWrite() {
    assertThat(WebInputs.version(3L)).isEqualTo(3);
    assertThat(WebInputs.version(0L)).isZero();
    assertThatThrownBy(() -> WebInputs.version(null))
        .isInstanceOfSatisfying(
            ValidationFailedException.class,
            e ->
                assertThat(e.violations())
                    .singleElement()
                    .satisfies(
                        v -> {
                          assertThat(v.field()).isEqualTo("version");
                          assertThat(v.code()).isEqualTo("REQUIRED");
                        }));
  }

  // Un identificador que no es un UUID no puede existir: se trata como inexistente, no como
  // inválido.
  @Test
  void aPostingIdThatIsNotAUuidDoesNotExist() {
    UUID id = UUID.randomUUID();

    assertThat(WebInputs.postingId(id.toString()).value()).isEqualTo(id);
    assertThatThrownBy(() -> WebInputs.postingId("no-es-un-uuid"))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> WebInputs.postingId("")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void aRequiredFieldThatIsMissingIsAValidationError() {
    assertThat(WebInputs.required("to", "PUBLISHED")).isEqualTo("PUBLISHED");
    assertThatThrownBy(() -> WebInputs.required("to", null))
        .isInstanceOfSatisfying(
            ValidationFailedException.class,
            e ->
                assertThat(e.violations())
                    .singleElement()
                    .satisfies(
                        v -> {
                          assertThat(v.field()).isEqualTo("to");
                          assertThat(v.code()).isEqualTo("REQUIRED");
                        }));
  }
}
