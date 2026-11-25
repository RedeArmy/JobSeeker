package com.usac.jobseeker.auth.application.usecase.recruiterverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.auth.application.usecase.changerecruiterverification.ChangeRecruiterVerificationCommand;
import com.usac.jobseeker.auth.application.usecase.changerecruiterverification.ChangeRecruiterVerificationService;
import com.usac.jobseeker.auth.application.usecase.changerecruiterverification.RecruiterVerificationStorePort;
import com.usac.jobseeker.auth.application.usecase.getrecruiterverification.GetRecruiterVerificationQuery;
import com.usac.jobseeker.auth.application.usecase.getrecruiterverification.GetRecruiterVerificationService;
import com.usac.jobseeker.auth.application.usecase.getrecruiterverification.RecruiterVerificationReadPort;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecruiterVerificationServiceTest {

  private static final AccountId RECRUITER = new AccountId(UUID.randomUUID());
  private static final AccountId CANDIDATE_ONLY = new AccountId(UUID.randomUUID());

  /** Almacén en memoria; la cuenta que no es reclutadora simplemente no está. */
  private static final class Store
      implements RecruiterVerificationReadPort, RecruiterVerificationStorePort {

    final Map<AccountId, RecruiterVerification> values = new HashMap<>();
    boolean loseTheRace;

    @Override
    public Optional<RecruiterVerification> findOfActiveRecruiter(AccountId accountId) {
      return Optional.ofNullable(values.get(accountId));
    }

    @Override
    public Optional<RecruiterVerification> findOfRecruiter(AccountId accountId) {
      return Optional.ofNullable(values.get(accountId));
    }

    @Override
    public boolean updateIfCurrent(
        AccountId accountId, RecruiterVerification expected, RecruiterVerification target) {
      if (loseTheRace || values.get(accountId) != expected) {
        return false;
      }
      values.put(accountId, target);
      return true;
    }
  }

  private final Store store = new Store();
  private final List<String> metrics = new ArrayList<>();
  private final ChangeRecruiterVerificationService change =
      new ChangeRecruiterVerificationService(store, (u, o) -> metrics.add(u + ":" + o));
  private final GetRecruiterVerificationService get =
      new GetRecruiterVerificationService(store, (u, o) -> metrics.add(u + ":" + o));

  RecruiterVerificationServiceTest() {
    store.values.put(RECRUITER, RecruiterVerification.UNVERIFIED);
  }

  @Test
  void anAccountThatIsNotARecruiterHasNoVerificationToRead() {
    assertThat(get.execute(new GetRecruiterVerificationQuery(CANDIDATE_ONLY))).isEmpty();
    assertThat(metrics).containsExactly("get_recruiter_verification:absent");
  }

  @Test
  void aRecruiterStartsUnverified() {
    assertThat(get.execute(new GetRecruiterVerificationQuery(RECRUITER)))
        .contains(RecruiterVerification.UNVERIFIED);
  }

  @Test
  void verifyingSuspendingAndReinstatingEachChangeTheValue() {
    assertThat(change.execute(command(RecruiterVerification.VERIFIED))).isTrue();
    assertThat(change.execute(command(RecruiterVerification.SUSPENDED))).isTrue();
    assertThat(change.execute(command(RecruiterVerification.VERIFIED))).isTrue();

    assertThat(get.execute(new GetRecruiterVerificationQuery(RECRUITER)))
        .contains(RecruiterVerification.VERIFIED);
    assertThat(metrics)
        .contains(
            "change_recruiter_verification:verified", "change_recruiter_verification:suspended");
  }

  @Test
  void askingForTheValueItAlreadyHasIsAnIdempotentNoOp() {
    assertThat(change.execute(command(RecruiterVerification.UNVERIFIED))).isFalse();
    assertThat(metrics).containsExactly("change_recruiter_verification:unchanged");
  }

  // Verificar a quien no es reclutador no tendría efecto y escondería un error del llamador.
  @Test
  void anAccountThatIsNotARecruiterCannotBeVerified() {
    var notRecruiter =
        new ChangeRecruiterVerificationCommand(CANDIDATE_ONLY, RecruiterVerification.VERIFIED);

    assertThatThrownBy(() -> change.execute(notRecruiter)).isInstanceOf(NotFoundException.class);
    assertThat(metrics).containsExactly("change_recruiter_verification:not_found");
  }

  @Test
  void anOperatorWhoLosesTheRaceGetsAStaleStateAndNothingIsWritten() {
    store.loseTheRace = true;

    assertThatThrownBy(() -> change.execute(command(RecruiterVerification.VERIFIED)))
        .isInstanceOf(StaleStateException.class);
    assertThat(store.values.get(RECRUITER)).isEqualTo(RecruiterVerification.UNVERIFIED);
  }

  // BR-DATA-01: ni la métrica ni el log llevan el identificador de la cuenta.
  @Test
  void metricsCarryNoAccountIdentifier() {
    change.execute(command(RecruiterVerification.VERIFIED));
    get.execute(new GetRecruiterVerificationQuery(RECRUITER));

    assertThat(metrics).noneMatch(m -> m.contains(RECRUITER.toString()));
  }

  private static ChangeRecruiterVerificationCommand command(RecruiterVerification target) {
    return new ChangeRecruiterVerificationCommand(RECRUITER, target);
  }
}
