package com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.InMemoryProfessionalProfiles;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetMyProfessionalProfileServiceTest {

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final InMemoryProfessionalProfiles store = new InMemoryProfessionalProfiles();
  private final List<String> metrics = new ArrayList<>();
  private final GetMyProfessionalProfileService service =
      new GetMyProfessionalProfileService(
          store, (useCase, outcome) -> metrics.add(useCase + ":" + outcome));

  @Test
  void anAccountThatNeverCreatedItsProfileGetsNothingAndIsNotAnError() {
    assertThat(service.execute(new GetMyProfessionalProfileQuery(account))).isEmpty();

    assertThat(metrics).containsExactly("get_my_professional_profile:not_created");
  }

  @Test
  void theProfileIsReturnedWithEveryFieldAndItsVersion() {
    var id = new ProfessionalProfileId(UUID.randomUUID());
    var updated = Instant.parse("2026-11-23T10:00:00Z");
    store.insertIfAbsent(
        new ProfessionalProfile(
            id, account, ProfessionalIdentity.of("Ana López", "Dev", "Bio"), 4, updated));

    var view = service.execute(new GetMyProfessionalProfileQuery(account)).orElseThrow();

    assertThat(view)
        .isEqualTo(new ProfessionalProfileView(id.value(), "Ana López", "Dev", "Bio", 4, updated));
    assertThat(metrics).containsExactly("get_my_professional_profile:found");
  }

  @Test
  void anAccountNeverSeesTheProfileOfAnother() {
    var other = new AccountId(UUID.randomUUID());
    store.insertIfAbsent(
        ProfessionalProfile.create(
            new ProfessionalProfileId(UUID.randomUUID()),
            other,
            ProfessionalIdentity.of("Otra", null, null),
            Instant.now()));

    assertThat(service.execute(new GetMyProfessionalProfileQuery(account))).isEmpty();
  }

  @Test
  void theQueryAndTheCollaboratorsAreRequired() {
    assertThatThrownBy(() -> new GetMyProfessionalProfileQuery(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new GetMyProfessionalProfileService(null, (a, b) -> {}))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new GetMyProfessionalProfileService(store, null))
        .isInstanceOf(NullPointerException.class);
  }
}
