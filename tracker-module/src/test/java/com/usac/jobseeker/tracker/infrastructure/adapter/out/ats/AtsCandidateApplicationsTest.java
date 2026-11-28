package com.usac.jobseeker.tracker.infrastructure.adapter.out.ats;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.ats.application.usecase.listcandidateapplications.CandidateApplication;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.tracker.application.support.CandidateApplicationsPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Traduce las postulaciones de `ats` a los tipos de `tracker`, con su historial. */
class AtsCandidateApplicationsTest {

  private static final Instant AT = Instant.parse("2026-11-28T10:00:00Z");

  @Test
  void everyFieldAndEveryChangeIsCarriedOver() {
    AccountId candidate = new AccountId(UUID.randomUUID());
    UUID application = UUID.randomUUID();
    UUID job = UUID.randomUUID();
    var adapter =
        new AtsCandidateApplications(
            query -> {
              assertThat(query.candidate()).isEqualTo(candidate);
              return List.of(
                  new CandidateApplication(
                      application,
                      job,
                      "REJECTED",
                      "OFFER",
                      4,
                      AT,
                      AT.plusSeconds(60),
                      List.of(
                          new CandidateApplication.Change(
                              null, null, "ACTIVE", "NEW", "CANDIDATE", AT),
                          new CandidateApplication.Change(
                              "ACTIVE", "OFFER", "REJECTED", "OFFER", "RECRUITER", AT))));
            });

    List<CandidateApplicationsPort.Tracked> result = adapter.of(candidate);

    assertThat(result)
        .containsExactly(
            new CandidateApplicationsPort.Tracked(
                application,
                job,
                "REJECTED",
                "OFFER",
                4,
                AT,
                AT.plusSeconds(60),
                List.of(
                    new CandidateApplicationsPort.Change(
                        null, null, "ACTIVE", "NEW", "CANDIDATE", AT),
                    new CandidateApplicationsPort.Change(
                        "ACTIVE", "OFFER", "REJECTED", "OFFER", "RECRUITER", AT))));
  }
}
