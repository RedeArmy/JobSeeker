package com.usac.jobseeker.tracker.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.tracker.application.support.CandidateApplicationsPort;
import com.usac.jobseeker.tracker.application.usecase.getmyapplication.GetMyApplicationQuery;
import com.usac.jobseeker.tracker.application.usecase.listmyapplications.ListMyApplicationsQuery;
import com.usac.jobseeker.tracker.application.usecase.listmyapplications.MyApplication;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El adaptador solo traduce: la cuenta del token al caso de uso y el resultado al contrato. */
class TrackerControllerTest {

  private static final Instant AT = Instant.parse("2026-11-28T10:00:00Z");

  private final UUID caller = UUID.randomUUID();
  private final Principal principal = caller::toString;
  private final UUID application = UUID.randomUUID();
  private final UUID job = UUID.randomUUID();
  private final List<Object> received = new ArrayList<>();

  private MyApplication mine() {
    return new MyApplication(
        application,
        job,
        "Backend",
        "DELETED",
        "WITHDRAWN",
        "NEW",
        2,
        AT,
        AT,
        List.of(
            new CandidateApplicationsPort.Change(null, null, "ACTIVE", "NEW", "CANDIDATE", AT)));
  }

  private final TrackerController controller =
      new TrackerController(
          query -> {
            received.add(query);
            return List.of(mine());
          },
          query -> {
            received.add(query);
            return mine();
          });

  @Test
  void theListCarriesThePostingTheStatusAndTheHistory() {
    List<MyApplicationResponse> list = controller.listMyApplications(principal);

    assertThat(((ListMyApplicationsQuery) received.get(0)).candidate().value()).isEqualTo(caller);
    assertThat(list)
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.id()).isEqualTo(application);
              assertThat(a.job())
                  .isEqualTo(new MyApplicationResponse.Job(job, "Backend", "DELETED"));
              assertThat(a.status()).isEqualTo("WITHDRAWN");
              assertThat(a.history())
                  .containsExactly(
                      new MyApplicationResponse.Change(
                          null, null, "ACTIVE", "NEW", "CANDIDATE", AT));
            });
  }

  @Test
  void theDetailAsksForTheOwnApplicationById() {
    MyApplicationResponse detail = controller.getMyApplication(principal, application.toString());

    GetMyApplicationQuery query = (GetMyApplicationQuery) received.get(0);
    assertThat(query.candidate().value()).isEqualTo(caller);
    assertThat(query.applicationId()).isEqualTo(application);
    assertThat(detail.version()).isEqualTo(2);
  }

  @Test
  void anIdThatIsNotAUuidDoesNotExist() {
    assertThatThrownBy(() -> controller.getMyApplication(principal, "x"))
        .isInstanceOf(NotFoundException.class);
    assertThat(received).isEmpty();
  }
}
