package com.usac.jobseeker.tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.tracker.application.support.CandidateApplicationsPort;
import com.usac.jobseeker.tracker.application.support.MyApplications;
import com.usac.jobseeker.tracker.application.support.PostingSummariesPort;
import com.usac.jobseeker.tracker.application.usecase.getmyapplication.GetMyApplicationQuery;
import com.usac.jobseeker.tracker.application.usecase.getmyapplication.GetMyApplicationService;
import com.usac.jobseeker.tracker.application.usecase.listmyapplications.ListMyApplicationsQuery;
import com.usac.jobseeker.tracker.application.usecase.listmyapplications.ListMyApplicationsService;
import com.usac.jobseeker.tracker.application.usecase.listmyapplications.MyApplication;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El tracker: compone las postulaciones de `ats` con las vacantes de `job` (TDD §4.2). */
class MyApplicationsTest {

  private static final Instant AT = Instant.parse("2026-11-28T10:00:00Z");

  private final AccountId candidate = new AccountId(UUID.randomUUID());
  private final UUID openJob = UUID.randomUUID();
  private final UUID deletedJob = UUID.randomUUID();
  private final List<CandidateApplicationsPort.Tracked> stored = new ArrayList<>();
  private final List<Collection<UUID>> postingReads = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private boolean isCandidate = true;

  private final MyApplications mine =
      new MyApplications(
          account -> isCandidate,
          account -> stored,
          ids -> {
            postingReads.add(ids);
            return Map.of(
                openJob,
                new PostingSummariesPort.Summary("Backend", "PUBLISHED"),
                deletedJob,
                new PostingSummariesPort.Summary("Frontend", "DELETED"));
          });
  private final ListMyApplicationsService list =
      new ListMyApplicationsService(mine, (u, o) -> metrics.add(o));
  private final GetMyApplicationService get =
      new GetMyApplicationService(mine, (u, o) -> metrics.add(o));

  private UUID tracked(UUID job, String status) {
    UUID id = UUID.randomUUID();
    stored.add(
        new CandidateApplicationsPort.Tracked(
            id,
            job,
            status,
            "NEW",
            1,
            AT,
            AT,
            List.of(
                new CandidateApplicationsPort.Change(
                    null, null, "ACTIVE", "NEW", "CANDIDATE", AT))));
    return id;
  }

  // BR-JOB-09: la postulación a una vacante eliminada sigue en la lista, con la vacante marcada.
  @Test
  void eachApplicationComesWithItsPostingEvenADeletedOne() {
    tracked(openJob, "ACTIVE");
    tracked(deletedJob, "ACTIVE");

    List<MyApplication> result = list.execute(new ListMyApplicationsQuery(candidate));

    assertThat(result).extracting(MyApplication::jobTitle).containsExactly("Backend", "Frontend");
    assertThat(result).extracting(MyApplication::jobStatus).containsExactly("PUBLISHED", "DELETED");
    assertThat(result.get(0).history()).hasSize(1);
    assertThat(postingReads).as("una sola lectura de vacantes").hasSize(1);
    assertThat(metrics).containsExactly("found");
  }

  @Test
  void withoutApplicationsJobIsNotAsked() {
    assertThat(list.execute(new ListMyApplicationsQuery(candidate))).isEmpty();
    assertThat(postingReads).isEmpty();
    assertThat(metrics).containsExactly("empty");
  }

  @Test
  void theDetailIsOneOfTheOwnAndAnythingElseDoesNotExist() {
    UUID id = tracked(openJob, "WITHDRAWN");

    assertThat(get.execute(new GetMyApplicationQuery(candidate, id)).status())
        .isEqualTo("WITHDRAWN");
    GetMyApplicationQuery other = new GetMyApplicationQuery(candidate, UUID.randomUUID());
    assertThatThrownBy(() -> get.execute(other)).isInstanceOf(NotFoundException.class);
    assertThat(metrics).containsExactly("found", "not_found");
  }

  @Test
  void withoutTheCandidateCapabilityNothingIsRead() {
    tracked(openJob, "ACTIVE");
    isCandidate = false;
    ListMyApplicationsQuery query = new ListMyApplicationsQuery(candidate);

    assertThatThrownBy(() -> list.execute(query)).isInstanceOf(ForbiddenCapabilityException.class);
    assertThat(postingReads).isEmpty();
    assertThat(metrics).containsExactly("forbidden");
  }
}
