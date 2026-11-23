package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.application.usecase.managecertifications.ManageCertificationsUseCase;
import com.usac.jobseeker.cv.application.usecase.manageeducation.ManageEducationUseCase;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.AddExperienceCommand;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.ManageExperiencesUseCase;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.RemoveExperienceCommand;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.UpdateExperienceCommand;
import com.usac.jobseeker.cv.application.usecase.manageprojects.ManageProjectsUseCase;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.SaveProfessionalProfileResult;
import com.usac.jobseeker.cv.domain.model.ProfileCollections;
import java.lang.reflect.Method;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.bind.annotation.ResponseStatus;

/** La pantalla solo traduce: la sesión al caso de uso y el resultado al fragmento HTML. */
class ProfileScreenControllerTest {

  private final UUID caller = UUID.randomUUID();
  private final UUID item = UUID.randomUUID();
  private final List<Object> commands = new ArrayList<>();
  private final ProfessionalProfileView view =
      new ProfessionalProfileView(
          UUID.randomUUID(),
          "Ana",
          null,
          null,
          4,
          Instant.parse("2026-11-23T10:00:00Z"),
          ProfileCollections.empty());

  private ProfessionalProfileView capture(Object command) {
    commands.add(command);
    return view;
  }

  private final ProfileScreenController controller =
      new ProfileScreenController(
          query -> java.util.Optional.of(view),
          command -> {
            commands.add(command);
            return new SaveProfessionalProfileResult(view, false);
          },
          new ManageExperiencesUseCase() {
            public ProfessionalProfileView add(AddExperienceCommand c) {
              return capture(c);
            }

            public ProfessionalProfileView update(UpdateExperienceCommand c) {
              return capture(c);
            }

            public ProfessionalProfileView remove(RemoveExperienceCommand c) {
              return capture(c);
            }
          },
          (ManageEducationUseCase) null,
          (ManageCertificationsUseCase) null,
          (ManageProjectsUseCase) null);

  private Principal who() {
    return caller::toString;
  }

  @Test
  void thePageShowsTheProfileOfTheSessionAccountAndNothingElse() {
    var model = new ConcurrentModel();

    String viewName = controller.page(who(), model);

    assertThat(viewName).isEqualTo("cv/profile");
    assertThat(model.getAttribute("profile"))
        .isInstanceOfSatisfying(
            ProfessionalProfileResponse.class, p -> assertThat(p.version()).isEqualTo(4));
  }

  @Test
  void savingTheIdentityAnswersTheContentFragmentWithTheProfile() {
    var model = new ConcurrentModel();

    String name =
        controller.saveIdentity(
            who(), model, new SaveProfessionalProfileRequest("Ana", "Dev", "Bio", 4L));

    assertThat(name).isEqualTo("cv/profile :: content");
    assertThat(model.getAttribute("profile")).isNotNull();
    assertThat(commands).hasSize(1);
  }

  @Test
  void aMutationSendsTheSessionAccountTheFormFieldsAndThePathId() {
    var model = new ConcurrentModel();
    var form = new ExperienceRequest("Acme", "Dev", LocalDate.of(2022, 3, 1), null, "d", 4L);

    controller.addExperience(who(), model, form);
    controller.updateExperience(who(), model, item, form);
    controller.removeExperience(who(), model, item, 4);

    assertThat(commands.get(0))
        .isInstanceOfSatisfying(
            AddExperienceCommand.class,
            c -> {
              assertThat(c.accountId().value()).isEqualTo(caller);
              assertThat(c.company()).isEqualTo("Acme");
            });
    assertThat(commands.get(1))
        .isInstanceOfSatisfying(
            UpdateExperienceCommand.class, c -> assertThat(c.itemId()).isEqualTo(item));
    assertThat(commands.get(2))
        .isInstanceOfSatisfying(
            RemoveExperienceCommand.class, c -> assertThat(c.expectedVersion()).isEqualTo(4));
  }

  @Test
  void aMissingVersionIsAValidationErrorBeforeReachingTheUseCase() {
    var model = new ConcurrentModel();
    var form = new SaveProfessionalProfileRequest("Ana", null, null, null);
    Principal principal = who();

    assertThatThrownBy(() -> controller.saveIdentity(principal, model, form))
        .isInstanceOfSatisfying(
            ValidationFailedException.class,
            e ->
                assertThat(e.violations())
                    .singleElement()
                    .isEqualTo(new FieldViolation("version", "REQUIRED")));
    assertThat(commands).isEmpty();
  }

  // ---- manejadores de error ----

  @Test
  void aValidationFailureAnswersOnlyTheErrorZoneWithTheMessagesInSpanish() {
    var model = new ConcurrentModel();

    String name =
        controller.invalid(
            new ValidationFailedException(List.of(new FieldViolation("company", "REQUIRED"))),
            model);

    assertThat(name).isEqualTo("cv/profile :: errors");
    assertThat(model.getAttribute("errors")).isEqualTo(List.of("La empresa es obligatorio."));
    assertThat(model.containsAttribute("profile")).isFalse();
  }

  @Test
  void aStaleVersionAndAMissingItemReloadTheContentWithANotice() {
    var stale = new ConcurrentModel();
    var absent = new ConcurrentModel();

    assertThat(controller.stale(who(), stale)).isEqualTo("cv/profile :: content");
    assertThat(controller.notFound(who(), absent)).isEqualTo("cv/profile :: content");

    assertThat(stale.getAttribute("notice")).asString().contains("El perfil cambió");
    assertThat(absent.getAttribute("notice")).asString().contains("ya no existe");
    assertThat(stale.getAttribute("profile")).isNotNull();
    assertThat(absent.getAttribute("profile")).isNotNull();
  }

  @Test
  void eachErrorHandlerDeclaresTheStatusThatTheHtmxConfigurationExpects() throws Exception {
    assertThat(
            statusOf(
                "invalid", ValidationFailedException.class, org.springframework.ui.Model.class))
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(statusOf("stale", Principal.class, org.springframework.ui.Model.class))
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(statusOf("notFound", Principal.class, org.springframework.ui.Model.class))
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  private static HttpStatus statusOf(String method, Class<?>... types) throws Exception {
    Method handler = ProfileScreenController.class.getDeclaredMethod(method, types);
    return handler.getAnnotation(ResponseStatus.class).value();
  }
}
