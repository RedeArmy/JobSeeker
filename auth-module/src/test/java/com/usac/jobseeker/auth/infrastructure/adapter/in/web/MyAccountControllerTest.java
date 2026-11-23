package com.usac.jobseeker.auth.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.auth.application.usecase.getmyaccount.GetMyAccountQuery;
import com.usac.jobseeker.auth.application.usecase.getmyaccount.GetMyAccountUseCase;
import com.usac.jobseeker.auth.application.usecase.getmyaccount.MyAccountView;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** El adaptador solo traduce: identidad del token a la consulta y resultado al contrato. */
class MyAccountControllerTest {

  private final UUID caller = UUID.randomUUID();
  private final List<GetMyAccountQuery> queries = new ArrayList<>();
  private Optional<MyAccountView> answer = Optional.empty();
  private final GetMyAccountUseCase useCase =
      query -> {
        queries.add(query);
        return answer;
      };
  private final MyAccountController controller = new MyAccountController(useCase);

  private static Principal principal(UUID id) {
    return id::toString;
  }

  @Test
  void theCallerIsTheSubjectOfTheTokenAndNothingElse() {
    answer = Optional.of(new MyAccountView(new AccountId(caller), Set.of(Capability.CANDIDATE)));

    controller.myAccount(principal(caller));

    assertThat(queries)
        .singleElement()
        .satisfies(q -> assertThat(q.accountId().value()).isEqualTo(caller));
  }

  @Test
  void theResponseCarriesTheIdAndTheCapabilitiesInAStableOrder() {
    answer =
        Optional.of(
            new MyAccountView(
                new AccountId(caller), Set.of(Capability.RECRUITER, Capability.CANDIDATE)));

    MyAccountResponse response = controller.myAccount(principal(caller));

    assertThat(response.id()).isEqualTo(caller.toString());
    assertThat(response.capabilities()).containsExactly(Capability.CANDIDATE, Capability.RECRUITER);
  }

  @Test
  void anAccountThatDoesNotExistIsA404() {
    Principal ghost = principal(caller);

    assertThatThrownBy(() -> controller.myAccount(ghost))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }
}
