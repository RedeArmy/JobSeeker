package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.GetMyProfessionalProfileQuery;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.SaveProfessionalProfileCommand;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.SaveProfessionalProfileResult;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** El adaptador solo traduce: identidad del token al caso de uso y resultado al contrato. */
class ProfessionalProfileControllerTest {

  private final UUID caller = UUID.randomUUID();
  private final List<GetMyProfessionalProfileQuery> queries = new ArrayList<>();
  private final List<SaveProfessionalProfileCommand> commands = new ArrayList<>();
  private Optional<ProfessionalProfileView> stored = Optional.empty();
  private SaveProfessionalProfileResult saved;
  private RuntimeException failure;

  private final ProfessionalProfileController controller =
      new ProfessionalProfileController(
          query -> {
            queries.add(query);
            return stored;
          },
          command -> {
            commands.add(command);
            if (failure != null) {
              throw failure;
            }
            return saved;
          });

  private static Principal principal(UUID id) {
    return id::toString;
  }

  private static ProfessionalProfileView view(long version) {
    return new ProfessionalProfileView(
        UUID.fromString("00000000-0000-0000-0000-000000000009"),
        "Ana López",
        "Dev",
        "Bio",
        version,
        Instant.parse("2026-11-23T10:00:00Z"));
  }

  @Test
  void theCallerIsTheSubjectOfTheTokenAndNothingElse() {
    controller.myProfile(principal(caller));

    assertThat(queries)
        .singleElement()
        .satisfies(q -> assertThat(q.accountId().value()).isEqualTo(caller));
  }

  @Test
  void aProfileThatDoesNotExistYetIsNotAnErrorAndAnnouncesVersionZero() {
    var response = controller.myProfile(principal(caller));

    assertThat(response.created()).isFalse();
    assertThat(response.version()).isZero();
    assertThat(response.id()).isNull();
    assertThat(response.fullName()).isNull();
  }

  @Test
  void anExistingProfileIsReturnedWithEveryField() {
    stored = Optional.of(view(4));

    var response = controller.myProfile(principal(caller));

    assertThat(response)
        .isEqualTo(
            new ProfessionalProfileResponse(
                true,
                "00000000-0000-0000-0000-000000000009",
                "Ana López",
                "Dev",
                "Bio",
                4,
                "2026-11-23T10:00:00Z"));
  }

  @Test
  void savingSendsTheCallerAndTheDataAndNeverAnIdentifierFromTheBody() {
    saved = new SaveProfessionalProfileResult(view(1), true);

    controller.saveMyProfile(
        principal(caller), new SaveProfessionalProfileRequest("Ana", "Dev", "Bio", 0L));

    assertThat(commands)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.accountId().value()).isEqualTo(caller);
              assertThat(c.fullName()).isEqualTo("Ana");
              assertThat(c.headline()).isEqualTo("Dev");
              assertThat(c.bio()).isEqualTo("Bio");
              assertThat(c.expectedVersion()).isZero();
            });
  }

  @Test
  void creatingAnswers201AndEditingAnswers200() {
    saved = new SaveProfessionalProfileResult(view(1), true);
    var created =
        controller.saveMyProfile(
            principal(caller), new SaveProfessionalProfileRequest("Ana", null, null, 0L));
    saved = new SaveProfessionalProfileResult(view(2), false);
    var edited =
        controller.saveMyProfile(
            principal(caller), new SaveProfessionalProfileRequest("Ana", null, null, 1L));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(edited.getBody())
        .isNotNull()
        .extracting(ProfessionalProfileResponse::version)
        .isEqualTo(2L);
  }

  @Test
  void aMissingVersionIsAValidationErrorBeforeReachingTheUseCase() {
    assertThatThrownBy(
            () ->
                controller.saveMyProfile(
                    principal(caller), new SaveProfessionalProfileRequest("Ana", null, null, null)))
        .isInstanceOf(ValidationFailedException.class)
        .satisfies(
            e ->
                assertThat(((ValidationFailedException) e).violations())
                    .singleElement()
                    .satisfies(
                        v -> {
                          assertThat(v.field()).isEqualTo("version");
                          assertThat(v.code()).isEqualTo("REQUIRED");
                        }));
    assertThat(commands).isEmpty();
  }

  @Test
  void theErrorsOfTheUseCaseReachTheGlobalHandlerUntouched() {
    failure = new StaleStateException();

    assertThatThrownBy(
            () ->
                controller.saveMyProfile(
                    principal(caller), new SaveProfessionalProfileRequest("Ana", null, null, 5L)))
        .isSameAs(failure);
  }
}
