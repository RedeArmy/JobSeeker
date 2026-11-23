package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.application.usecase.managecertifications.AddCertificationCommand;
import com.usac.jobseeker.cv.application.usecase.managecertifications.ManageCertificationsUseCase;
import com.usac.jobseeker.cv.application.usecase.managecertifications.RemoveCertificationCommand;
import com.usac.jobseeker.cv.application.usecase.managecertifications.UpdateCertificationCommand;
import com.usac.jobseeker.cv.application.usecase.manageeducation.AddEducationCommand;
import com.usac.jobseeker.cv.application.usecase.manageeducation.ManageEducationUseCase;
import com.usac.jobseeker.cv.application.usecase.manageeducation.RemoveEducationCommand;
import com.usac.jobseeker.cv.application.usecase.manageeducation.UpdateEducationCommand;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.AddExperienceCommand;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.ManageExperiencesUseCase;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.RemoveExperienceCommand;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.UpdateExperienceCommand;
import com.usac.jobseeker.cv.application.usecase.manageprojects.AddProjectCommand;
import com.usac.jobseeker.cv.application.usecase.manageprojects.ManageProjectsUseCase;
import com.usac.jobseeker.cv.application.usecase.manageprojects.RemoveProjectCommand;
import com.usac.jobseeker.cv.application.usecase.manageprojects.UpdateProjectCommand;
import com.usac.jobseeker.cv.domain.model.EducationLevel;
import com.usac.jobseeker.cv.domain.model.ProfileCollections;
import com.usac.jobseeker.cv.domain.model.ProjectType;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** El adaptador solo traduce: la identidad sale del token y cada campo llega a su comando. */
class ProfileCollectionsControllerTest {

  private static final LocalDate JAN = LocalDate.of(2020, 1, 1);
  private static final LocalDate DEC = LocalDate.of(2020, 12, 31);

  private final UUID caller = UUID.randomUUID();
  private final UUID item = UUID.randomUUID();
  private final List<Object> commands = new ArrayList<>();
  private RuntimeException failure;

  private final ProfessionalProfileView view =
      new ProfessionalProfileView(
          UUID.fromString("00000000-0000-0000-0000-000000000009"),
          "Ana",
          null,
          null,
          5,
          Instant.parse("2026-11-23T10:00:00Z"),
          ProfileCollections.empty());

  private ProfessionalProfileView record(Object command) {
    commands.add(command);
    if (failure != null) {
      throw failure;
    }
    return view;
  }

  private final ProfileCollectionsController controller =
      new ProfileCollectionsController(
          new ManageExperiencesUseCase() {
            public ProfessionalProfileView add(AddExperienceCommand c) {
              return record(c);
            }

            public ProfessionalProfileView update(UpdateExperienceCommand c) {
              return record(c);
            }

            public ProfessionalProfileView remove(RemoveExperienceCommand c) {
              return record(c);
            }
          },
          new ManageEducationUseCase() {
            public ProfessionalProfileView add(AddEducationCommand c) {
              return record(c);
            }

            public ProfessionalProfileView update(UpdateEducationCommand c) {
              return record(c);
            }

            public ProfessionalProfileView remove(RemoveEducationCommand c) {
              return record(c);
            }
          },
          new ManageCertificationsUseCase() {
            public ProfessionalProfileView add(AddCertificationCommand c) {
              return record(c);
            }

            public ProfessionalProfileView update(UpdateCertificationCommand c) {
              return record(c);
            }

            public ProfessionalProfileView remove(RemoveCertificationCommand c) {
              return record(c);
            }
          },
          new ManageProjectsUseCase() {
            public ProfessionalProfileView add(AddProjectCommand c) {
              return record(c);
            }

            public ProfessionalProfileView update(UpdateProjectCommand c) {
              return record(c);
            }

            public ProfessionalProfileView remove(RemoveProjectCommand c) {
              return record(c);
            }
          });

  private Principal who() {
    return caller::toString;
  }

  // ---- experiencias ----

  @Test
  void addingAnExperienceSendsTheCallerAndEveryFieldAndAnswers201WithTheWholeProfile() {
    var response =
        controller.addExperience(who(), new ExperienceRequest("Acme", "Dev", JAN, DEC, "desc", 3L));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody())
        .isNotNull()
        .extracting(ProfessionalProfileResponse::version)
        .isEqualTo(5L);
    assertThat(commands)
        .singleElement()
        .isEqualTo(
            new AddExperienceCommand(
                new com.usac.jobseeker.common.domain.model.AccountId(caller),
                3,
                "Acme",
                "Dev",
                JAN,
                DEC,
                "desc"));
  }

  @Test
  void updatingAnExperienceSendsTheIdFromThePathAndNeverFromTheBody() {
    controller.updateExperience(
        who(), item, new ExperienceRequest("Acme", "Dev", JAN, null, null, 4L));

    assertThat(commands)
        .singleElement()
        .satisfies(
            c -> {
              var update = (UpdateExperienceCommand) c;
              assertThat(update.itemId()).isEqualTo(item);
              assertThat(update.accountId().value()).isEqualTo(caller);
              assertThat(update.expectedVersion()).isEqualTo(4);
            });
  }

  @Test
  void removingAnExperienceTakesTheVersionFromTheQueryParameter() {
    controller.removeExperience(who(), item, 7);

    assertThat(commands)
        .singleElement()
        .isEqualTo(
            new RemoveExperienceCommand(
                new com.usac.jobseeker.common.domain.model.AccountId(caller), 7, item));
  }

  // ---- el resto de colecciones ----

  @Test
  void anEducationCarriesItsLevel() {
    controller.addEducation(
        who(), new EducationRequest("USAC", "Ing", EducationLevel.MASTERS, DEC, 1L));

    assertThat(((AddEducationCommand) commands.get(0)).level()).isEqualTo(EducationLevel.MASTERS);
    controller.updateEducation(who(), item, new EducationRequest("USAC", "Ing", null, null, 2L));
    controller.removeEducation(who(), item, 3);
    assertThat(commands).hasSize(3);
  }

  @Test
  void aCertificationCarriesItsLink() {
    controller.addCertification(
        who(), new CertificationRequest("AWS", "Amazon", JAN, DEC, "https://example.test/c", 1L));

    assertThat(((AddCertificationCommand) commands.get(0)).credentialUrl())
        .isEqualTo("https://example.test/c");
    controller.updateCertification(
        who(), item, new CertificationRequest("AWS", "Amazon", null, null, null, 2L));
    controller.removeCertification(who(), item, 3);
    assertThat(commands).hasSize(3);
  }

  @Test
  void aProjectCarriesItsType() {
    controller.addProject(
        who(),
        new ProjectRequest(
            "P", "d", ProjectType.PERSONAL, "r", JAN, DEC, "https://example.test", 1L));

    assertThat(((AddProjectCommand) commands.get(0)).type()).isEqualTo(ProjectType.PERSONAL);
    controller.updateProject(
        who(), item, new ProjectRequest("P", null, ProjectType.WORK, null, null, null, null, 2L));
    controller.removeProject(who(), item, 3);
    assertThat(commands).hasSize(3);
  }

  // ---- errores ----

  @Test
  void aMissingVersionIsAValidationErrorBeforeReachingTheUseCase() {
    var request = new ExperienceRequest("Acme", "Dev", JAN, null, null, null);

    assertThatThrownBy(() -> controller.addExperience(who(), request))
        .isInstanceOfSatisfying(
            ValidationFailedException.class,
            e ->
                assertThat(e.violations())
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
    failure = new NotFoundException();
    var request = new EducationRequest("U", "D", null, null, 1L);
    Principal principal = who();

    assertThatThrownBy(() -> controller.updateEducation(principal, item, request))
        .isSameAs(failure);
  }
}
