package com.usac.jobseeker.ats.infrastructure.adapter.out.cv;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.ats.application.support.ApplicantProfilesPort;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getapplicantprofiles.ApplicantProfile;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Traduce los datos de `cv` a los de `ats`, también el marcador de un anonimizado. */
class CvApplicantProfilesTest {

  @Test
  void eachApplicantIsKeyedByAccountWithItsContactOrThePlaceholder() {
    AccountId ana = new AccountId(UUID.randomUUID());
    AccountId gone = new AccountId(UUID.randomUUID());
    var adapter =
        new CvApplicantProfiles(
            query -> {
              assertThat(query.accounts()).containsExactly(ana, gone);
              return List.of(
                  new ApplicantProfile(
                      ana.value(), "Ana", "Backend", "ana@example.test", "+502 1", false),
                  new ApplicantProfile(
                      gone.value(), "Candidato eliminado", null, null, null, true));
            });

    var result = adapter.of(List.of(ana, gone));

    assertThat(result.get(ana))
        .isEqualTo(
            new ApplicantProfilesPort.Profile(
                "Ana", "Backend", "ana@example.test", "+502 1", false));
    assertThat(result.get(gone).anonymized()).isTrue();
    assertThat(result.get(gone).contactEmail()).isNull();
  }
}
