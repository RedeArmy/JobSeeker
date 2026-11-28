package com.usac.jobseeker.ats.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.ats.application.usecase.listcandidateapplications.CandidateApplication;
import com.usac.jobseeker.ats.application.usecase.submitapplication.SubmitApplicationCommand;
import com.usac.jobseeker.ats.application.usecase.submitapplication.SubmittedApplication;
import com.usac.jobseeker.ats.application.usecase.withdrawapplication.WithdrawApplicationCommand;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Postular y retirar: el candidato sale del token y el resultado se traduce al contrato. */
class ApplicationControllerTest {

  private static final Instant AT = Instant.parse("2026-11-28T10:00:00Z");

  private final UUID caller = UUID.randomUUID();
  private final Principal principal = caller::toString;
  private final UUID job = UUID.randomUUID();
  private final UUID application = UUID.randomUUID();
  private final List<Object> received = new ArrayList<>();

  private final ApplicationController controller =
      new ApplicationController(
          command -> {
            received.add(command);
            return new SubmittedApplication(application, job, "ACTIVE", "NEW", 1, AT);
          },
          command -> {
            received.add(command);
            return new CandidateApplication(
                application,
                job,
                "WITHDRAWN",
                "NEW",
                2,
                AT,
                AT,
                List.of(
                    new CandidateApplication.Change(null, null, "ACTIVE", "NEW", "CANDIDATE", AT),
                    new CandidateApplication.Change(
                        "ACTIVE", "NEW", "WITHDRAWN", "NEW", "CANDIDATE", AT)));
          });

  @Test
  void submittingAnswersCreatedWithTheApplicationAndPassesTheKey() {
    var response = controller.submitApplication(principal, job.toString(), "intento-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody().id()).isEqualTo(application);
    SubmitApplicationCommand command = (SubmitApplicationCommand) received.get(0);
    assertThat(command.candidate().value()).isEqualTo(caller);
    assertThat(command.idempotencyKey()).isEqualTo("intento-1");
  }

  @Test
  void withdrawingReturnsTheApplicationWithItsHistory() {
    CandidateApplicationResponse response =
        controller.withdrawApplication(principal, application.toString());

    WithdrawApplicationCommand command = (WithdrawApplicationCommand) received.get(0);
    assertThat(command.candidate().value()).isEqualTo(caller);
    assertThat(command.applicationId()).isEqualTo(application);
    assertThat(response.status()).isEqualTo("WITHDRAWN");
    assertThat(response.history())
        .containsExactly(
            new CandidateApplicationResponse.Change(null, null, "ACTIVE", "NEW", "CANDIDATE", AT),
            new CandidateApplicationResponse.Change(
                "ACTIVE", "NEW", "WITHDRAWN", "NEW", "CANDIDATE", AT));
  }

  @Test
  void anIdThatIsNotAUuidDoesNotExist() {
    assertThatThrownBy(() -> controller.withdrawApplication(principal, "x"))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> controller.submitApplication(principal, "x", null))
        .isInstanceOf(NotFoundException.class);
    assertThat(received).isEmpty();
  }
}
