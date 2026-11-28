package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getapplicantprofiles.ApplicantProfile;
import com.usac.jobseeker.cv.application.usecase.getapplicantprofiles.GetApplicantProfilesQuery;
import com.usac.jobseeker.cv.application.usecase.getapplicantprofiles.GetApplicantProfilesService;
import com.usac.jobseeker.cv.domain.model.Anonymization;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.infrastructure.CvPostgres;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lo que el reclutador de una postulación ve del candidato, contra Postgres real (REQ-C-07):
 * nombre, titular y contacto, y para una cuenta anonimizada el marcador sin contacto (BR-DATA-05).
 */
class JdbcApplicantProfileReaderTest {

  private static final Instant T0 = Instant.parse("2026-11-28T10:00:00Z");

  private final JdbcClient jdbc = JdbcClient.create(CvPostgres.dataSource());
  private final JdbcProfessionalProfileRepository professionals =
      new JdbcProfessionalProfileRepository(jdbc);
  private final JdbcCandidateProfileRepository candidates =
      new JdbcCandidateProfileRepository(jdbc);
  private final JdbcAnonymizationRepository anonymization = new JdbcAnonymizationRepository(jdbc);
  private final List<String> metrics = new ArrayList<>();
  private final GetApplicantProfilesService service =
      new GetApplicantProfilesService(
          new JdbcApplicantProfileReader(jdbc), (u, o) -> metrics.add(o));

  private AccountId applicant(String name, String email, String phone) {
    AccountId account = new AccountId(UUID.randomUUID());
    jdbc.sql("insert into accounts (id, is_candidate_enabled) values (:id, true)")
        .param("id", account.value())
        .update();
    professionals.insertIfAbsent(
        ProfessionalProfile.create(
            new ProfessionalProfileId(UUID.randomUUID()),
            account,
            ProfessionalIdentity.of(name, "Backend", null),
            T0));
    candidates.insertIfAbsent(
        CandidateProfile.create(
            new CandidateProfileId(UUID.randomUUID()),
            account,
            CandidateDetails.of(email, phone, null, null, null, null, null, null, null, null),
            T0));
    return account;
  }

  private List<ApplicantProfile> read(AccountId... accounts) {
    return service.execute(new GetApplicantProfilesQuery(List.of(accounts)));
  }

  @Test
  void severalApplicantsAreReadTogetherWithNameHeadlineAndContact() {
    AccountId ana = applicant("Ana", "ana@example.test", "+502 5555-0000");
    AccountId luis = applicant("Luis", "luis@example.test", null);

    List<ApplicantProfile> found = read(ana, luis, new AccountId(UUID.randomUUID()));

    assertThat(found)
        .extracting(ApplicantProfile::fullName)
        .containsExactlyInAnyOrder("Ana", "Luis");
    ApplicantProfile first =
        found.stream().filter(p -> p.accountId().equals(ana.value())).findFirst().orElseThrow();
    assertThat(first.headline()).isEqualTo("Backend");
    assertThat(first.contactEmail()).isEqualTo("ana@example.test");
    assertThat(first.contactPhone()).isEqualTo("+502 5555-0000");
    assertThat(first.anonymized()).isFalse();
    assertThat(metrics).containsExactly("read");
  }

  // BR-DATA-05: la postulación sigue en el pipeline, con el marcador y sin forma de contactar.
  @Test
  void anAnonymizedApplicantKeepsOnlyThePlaceholder() {
    AccountId ana = applicant("Ana", "ana@example.test", "+502 5555-0000");
    anonymization.anonymizeProfessional(ana, Anonymization.PLACEHOLDER_NAME, T0);
    anonymization.anonymizeCandidate(ana, T0);

    assertThat(read(ana))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.fullName()).isEqualTo(Anonymization.PLACEHOLDER_NAME);
              assertThat(p.headline()).isNull();
              assertThat(p.contactEmail()).isNull();
              assertThat(p.contactPhone()).isNull();
              assertThat(p.anonymized()).isTrue();
            });
  }

  @Test
  void anEmptyRequestReadsNothing() {
    assertThat(read()).isEmpty();
    assertThat(new JdbcApplicantProfileReader(jdbc).findApplicants(List.of())).isEmpty();
    assertThat(metrics).isEmpty();
  }
}
