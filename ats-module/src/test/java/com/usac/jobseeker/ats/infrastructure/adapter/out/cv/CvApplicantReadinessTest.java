package com.usac.jobseeker.ats.infrastructure.adapter.out.cv;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.checkapplicationreadiness.ApplicationReadiness;
import com.usac.jobseeker.cv.application.usecase.checkapplicationreadiness.MissingRequirement;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Lo que falta según `cv` viaja como sus códigos estables, ordenados (BR-CAND-01). */
class CvApplicantReadinessTest {

  private final AccountId candidate = new AccountId(UUID.randomUUID());

  @Test
  void aReadyCandidateCarriesItsProfileAndCv() {
    UUID profile = UUID.randomUUID();
    UUID cv = UUID.randomUUID();
    var adapter =
        new CvApplicantReadiness(
            query -> {
              assertThat(query.accountId()).isEqualTo(candidate);
              return new ApplicationReadiness(Set.of(), Optional.of(profile), Optional.of(cv));
            });

    var readiness = adapter.check(candidate);

    assertThat(readiness.ready()).isTrue();
    assertThat(readiness.candidateProfileId()).contains(profile);
    assertThat(readiness.cvFileId()).contains(cv);
  }

  @Test
  void whatIsMissingComesAsSortedCodes() {
    var adapter =
        new CvApplicantReadiness(
            query ->
                new ApplicationReadiness(
                    Set.of(MissingRequirement.CV, MissingRequirement.CONTACT),
                    Optional.empty(),
                    Optional.empty()));

    var readiness = adapter.check(candidate);

    assertThat(readiness.ready()).isFalse();
    assertThat(readiness.missing()).containsExactly("CONTACT", "CV");
  }
}
