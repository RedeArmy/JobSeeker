package com.usac.jobseeker.ats.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.InvalidTransitionException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * La tabla de {@link ApplicationMove} contra todo lo posible: cada movimiento desde cada estado,
 * cada columna y cada iniciador (tdd/ats-tracker-modules.md §9). Los válidos dejan exactamente lo
 * que dice la tabla; los demás fallan con `INVALID_TRANSITION` sin dejar nada.
 */
class ApplicationStateMachineTest {

  private static final Instant APPLIED = Instant.parse("2026-11-27T10:00:00Z");
  private static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");
  private static final UUID CHANGE = UUID.randomUUID();

  static Stream<Arguments> everyCombination() {
    List<Arguments> all = new ArrayList<>();
    for (ApplicationMove move : ApplicationMove.values()) {
      for (ApplicationStatus status : ApplicationStatus.values()) {
        for (PipelineStage stage : PipelineStage.values()) {
          for (ChangeInitiator by : ChangeInitiator.values()) {
            all.add(Arguments.of(move, status, stage, by));
          }
        }
      }
    }
    return all.stream();
  }

  @ParameterizedTest(name = "{0} desde {1}/{2} por {3}")
  @MethodSource("everyCombination")
  void everyMoveDoesExactlyWhatTheTableSays(
      ApplicationMove move, ApplicationStatus status, PipelineStage stage, ChangeInitiator by) {
    Application before = in(status, stage);
    boolean valid = expectedValid(move, status, by);

    if (!valid) {
      assertThatThrownBy(() -> apply(move, before, by))
          .isInstanceOf(InvalidTransitionException.class);
      return;
    }
    Application.Transition transition = apply(move, before, by);
    Application after = transition.application();
    PipelineStage expectedStage = move == ApplicationMove.CHANGE_STAGE ? another(stage) : stage;
    RejectionReason expectedReason =
        move == ApplicationMove.REJECT ? RejectionReason.NOT_A_FIT : null;

    assertThat(after.status()).isEqualTo(move.target());
    assertThat(after.stage()).isEqualTo(expectedStage);
    assertThat(after.rejectionReason()).isEqualTo(expectedReason);
    assertThat(after.version()).isEqualTo(before.version() + 1);
    assertThat(after.updatedAt()).isEqualTo(NOW);
    assertThat(after.appliedAt()).isEqualTo(APPLIED);
    assertThat(after)
        .usingRecursiveComparison()
        .comparingOnlyFields(
            "id", "candidateProfileId", "candidateAccountId", "jobPostingId", "cvFileId")
        .isEqualTo(before);
    assertThat(transition.change())
        .isEqualTo(
            new StatusChange(
                CHANGE,
                before.id(),
                status,
                stage,
                move.target(),
                expectedStage,
                by,
                expectedReason,
                NOW));
  }

  // Lo que la tabla del TDD dice, escrito aparte de ApplicationMove para que la prueba no se
  // compruebe contra sí misma.
  private static boolean expectedValid(
      ApplicationMove move, ApplicationStatus status, ChangeInitiator by) {
    return switch (move) {
      case CHANGE_STAGE, REJECT, HIRE ->
          status == ApplicationStatus.ACTIVE && by == ChangeInitiator.RECRUITER;
      case WITHDRAW -> status == ApplicationStatus.ACTIVE && by == ChangeInitiator.CANDIDATE;
      case REOPEN -> status == ApplicationStatus.REJECTED && by == ChangeInitiator.RECRUITER;
    };
  }

  @org.junit.jupiter.api.Test
  void exactlyTwentyCombinationsAreValid() {
    long valid =
        everyCombination()
            .map(Arguments::get)
            .filter(
                a ->
                    expectedValid(
                        (ApplicationMove) a[0], (ApplicationStatus) a[1], (ChangeInitiator) a[3]))
            .count();

    // Cinco movimientos, cada uno desde un solo estado y por un solo iniciador, en cuatro columnas.
    assertThat(valid).isEqualTo(20);
  }

  private static Application.Transition apply(
      ApplicationMove move, Application application, ChangeInitiator by) {
    return switch (move) {
      case CHANGE_STAGE -> application.changeStage(another(application.stage()), by, CHANGE, NOW);
      case REJECT -> application.reject(RejectionReason.NOT_A_FIT, by, CHANGE, NOW);
      case HIRE -> application.hire(by, CHANGE, NOW);
      case WITHDRAW -> application.withdraw(by, CHANGE, NOW);
      case REOPEN -> application.reopen(by, CHANGE, NOW);
    };
  }

  private static PipelineStage another(PipelineStage stage) {
    PipelineStage[] all = PipelineStage.values();
    return all[(stage.ordinal() + 1) % all.length];
  }

  static Application in(ApplicationStatus status, PipelineStage stage) {
    return new Application(
        new ApplicationId(UUID.randomUUID()),
        UUID.randomUUID(),
        new AccountId(UUID.randomUUID()),
        UUID.randomUUID(),
        UUID.randomUUID(),
        status,
        stage,
        status == ApplicationStatus.REJECTED ? RejectionReason.CANDIDATE_UNRESPONSIVE : null,
        3,
        APPLIED,
        APPLIED);
  }
}
