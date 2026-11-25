package com.usac.jobseeker.auth.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.auth.application.usecase.reviewrecruiter.ReviewRecruiterCommand;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El adaptador solo traduce: el operador sale del token y la cuenta de la ruta. */
class RecruiterReviewControllerTest {

  private final UUID operator = UUID.randomUUID();
  private final UUID target = UUID.randomUUID();
  private final List<ReviewRecruiterCommand> received = new ArrayList<>();
  private boolean changed = true;

  private final RecruiterReviewController controller =
      new RecruiterReviewController(
          command -> {
            received.add(command);
            return changed;
          });

  private static Principal principal(UUID id) {
    return id::toString;
  }

  @Test
  void theOperatorIsTheSubjectOfTheTokenAndTheTargetIsTheOneInThePath() {
    var response =
        controller.review(
            principal(operator),
            target.toString(),
            new RecruiterReviewRequest(RecruiterVerification.VERIFIED));

    assertThat(received)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.operator().value()).isEqualTo(operator);
              assertThat(c.target().value()).isEqualTo(target);
              assertThat(c.decision()).isEqualTo(RecruiterVerification.VERIFIED);
            });
    assertThat(response.status()).isEqualTo(RecruiterVerification.VERIFIED);
    assertThat(response.changed()).isTrue();
  }

  @Test
  void theResponseSaysWhenNothingChanged() {
    changed = false;

    var response =
        controller.review(
            principal(operator),
            target.toString(),
            new RecruiterReviewRequest(RecruiterVerification.SUSPENDED));

    assertThat(response.changed()).isFalse();
    assertThat(response.status()).isEqualTo(RecruiterVerification.SUSPENDED);
  }

  @Test
  void aMissingStatusOrBodyIsAValidationErrorBeforeAnyUseCaseRuns() {
    Principal who = principal(operator);
    String path = target.toString();
    var noStatus = new RecruiterReviewRequest(null);

    assertThatThrownBy(() -> controller.review(who, path, noStatus))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(() -> controller.review(who, path, null))
        .isInstanceOf(ValidationFailedException.class);
    assertThat(received).isEmpty();
  }

  // Un identificador que no es un UUID no puede ser de una cuenta: es inexistente, no inválido.
  @Test
  void anAccountIdThatIsNotAUuidDoesNotExist() {
    Principal who = principal(operator);
    var body = new RecruiterReviewRequest(RecruiterVerification.VERIFIED);

    assertThatThrownBy(() -> controller.review(who, "no-es-uuid", body))
        .isInstanceOf(NotFoundException.class);
    assertThat(received).isEmpty();
  }
}
