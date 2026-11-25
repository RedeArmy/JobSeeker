package com.usac.jobseeker.auth.application.usecase.reviewrecruiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.auth.application.usecase.changerecruiterverification.ChangeRecruiterVerificationCommand;
import com.usac.jobseeker.common.application.audit.AuditEntry;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Los tres controles del canal: ser operador, no decidir sobre uno mismo y auditar el cambio. */
class ReviewRecruiterServiceTest {

  private static final AccountId OPERATOR = new AccountId(UUID.randomUUID());
  private static final AccountId RECRUITER = new AccountId(UUID.randomUUID());

  private final Set<AccountId> operators = new HashSet<>(Set.of(OPERATOR));
  private final List<ChangeRecruiterVerificationCommand> changes = new ArrayList<>();
  private final List<AuditEntry> audit = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private boolean targetIsRecruiter = true;
  private boolean alreadyHasThatValue;
  private RuntimeException changeFailure;

  private final ReviewRecruiterService service =
      new ReviewRecruiterService(
          operators::contains,
          command -> {
            changes.add(command);
            if (changeFailure != null) {
              throw changeFailure;
            }
            if (!targetIsRecruiter) {
              throw new NotFoundException();
            }
            return !alreadyHasThatValue;
          },
          audit::add,
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome));

  private boolean review(AccountId operator, AccountId target, RecruiterVerification decision) {
    return service.execute(new ReviewRecruiterCommand(operator, target, decision));
  }

  @Test
  void anOperatorVerifiesARecruiterAndTheChangeIsAudited() {
    boolean changed = review(OPERATOR, RECRUITER, RecruiterVerification.VERIFIED);

    assertThat(changed).isTrue();
    assertThat(changes)
        .containsExactly(
            new ChangeRecruiterVerificationCommand(RECRUITER, RecruiterVerification.VERIFIED));
    assertThat(audit)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.actor()).isEqualTo(OPERATOR);
              assertThat(e.action()).isEqualTo("RECRUITER_VERIFICATION_CHANGED");
              assertThat(e.targetType()).isEqualTo("ACCOUNT");
              assertThat(e.targetId()).isEqualTo(RECRUITER.value());
              assertThat(e.details()).containsExactlyEntriesOf(java.util.Map.of("to", "VERIFIED"));
            });
    assertThat(metrics).containsExactly("review_recruiter:changed");
  }

  @Test
  void suspendingAndReturningToUnverifiedAreAuditedWithTheirOwnValue() {
    review(OPERATOR, RECRUITER, RecruiterVerification.SUSPENDED);
    review(OPERATOR, RECRUITER, RecruiterVerification.UNVERIFIED);

    assertThat(audit)
        .extracting(e -> e.details().get("to"))
        .containsExactly("SUSPENDED", "UNVERIFIED");
  }

  // El canal no existe para quien no es operador: ni cambia nada ni confirma que haya canal.
  @Test
  void aNonOperatorGetsNotFoundAndNothingChangesOrIsAudited() {
    var stranger = new AccountId(UUID.randomUUID());

    assertThatThrownBy(() -> review(stranger, RECRUITER, RecruiterVerification.VERIFIED))
        .isInstanceOf(NotFoundException.class);

    assertThat(changes).isEmpty();
    assertThat(audit).isEmpty();
    assertThat(metrics).containsExactly("review_recruiter:not_operator");
  }

  @Test
  void whenNobodyIsConfiguredAsOperatorNobodyCanReview() {
    operators.clear();

    assertThatThrownBy(() -> review(OPERATOR, RECRUITER, RecruiterVerification.VERIFIED))
        .isInstanceOf(NotFoundException.class);
    assertThat(changes).isEmpty();
  }

  // Nadie se verifica a sí mismo: un operador que también es reclutador no se da el tope alto.
  @Test
  void anOperatorCannotReviewItself() {
    assertThatThrownBy(() -> review(OPERATOR, OPERATOR, RecruiterVerification.VERIFIED))
        .isInstanceOfSatisfying(
            ValidationFailedException.class,
            e ->
                assertThat(e.violations())
                    .singleElement()
                    .satisfies(
                        v -> {
                          assertThat(v.field()).isEqualTo("accountId");
                          assertThat(v.code()).isEqualTo("SELF_REVIEW_FORBIDDEN");
                        }));

    assertThat(changes).isEmpty();
    assertThat(audit).isEmpty();
    assertThat(metrics).containsExactly("review_recruiter:self_review");
  }

  // Repetir la decisión no cambia nada y no deja un rastro de algo que no ocurrió.
  @Test
  void repeatingTheSameDecisionIsIdempotentAndLeavesNoAuditTrail() {
    alreadyHasThatValue = true;

    boolean changed = review(OPERATOR, RECRUITER, RecruiterVerification.VERIFIED);

    assertThat(changed).isFalse();
    assertThat(audit).isEmpty();
    assertThat(metrics).containsExactly("review_recruiter:unchanged");
  }

  @Test
  void anAccountThatIsNotARecruiterIsNotFoundAndNothingIsAudited() {
    targetIsRecruiter = false;

    assertThatThrownBy(() -> review(OPERATOR, RECRUITER, RecruiterVerification.VERIFIED))
        .isInstanceOf(NotFoundException.class);

    assertThat(audit).isEmpty();
  }

  @Test
  void aLostRaceIsAStaleStateAndIsNotAudited() {
    changeFailure = new StaleStateException();

    assertThatThrownBy(() -> review(OPERATOR, RECRUITER, RecruiterVerification.VERIFIED))
        .isInstanceOf(StaleStateException.class);

    assertThat(audit).isEmpty();
  }

  // Un operador puede ser candidato o no tener otra capacidad: lo único que cuenta es la lista.
  @Test
  void theOperatorDoesNotNeedToBeARecruiterItself() {
    assertThat(review(OPERATOR, RECRUITER, RecruiterVerification.VERIFIED)).isTrue();
  }

  // BR-DATA-01: ni la métrica ni el detalle de la auditoría llevan identificadores de cuenta.
  @Test
  void neitherTheMetricsNorTheAuditDetailsCarryAnAccountIdentifier() {
    review(OPERATOR, RECRUITER, RecruiterVerification.VERIFIED);

    assertThat(metrics)
        .noneMatch(m -> m.contains(RECRUITER.toString()) || m.contains(OPERATOR.toString()));
    assertThat(audit.get(0).details().toString())
        .doesNotContain(RECRUITER.toString())
        .doesNotContain(OPERATOR.toString());
  }
}
