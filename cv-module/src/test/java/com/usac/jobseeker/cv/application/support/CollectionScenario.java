package com.usac.jobseeker.cv.application.support;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.application.usecase.managecertifications.AddCertificationCommand;
import com.usac.jobseeker.cv.application.usecase.managecertifications.ManageCertificationsService;
import com.usac.jobseeker.cv.application.usecase.managecertifications.RemoveCertificationCommand;
import com.usac.jobseeker.cv.application.usecase.managecertifications.UpdateCertificationCommand;
import com.usac.jobseeker.cv.application.usecase.manageeducation.AddEducationCommand;
import com.usac.jobseeker.cv.application.usecase.manageeducation.ManageEducationService;
import com.usac.jobseeker.cv.application.usecase.manageeducation.RemoveEducationCommand;
import com.usac.jobseeker.cv.application.usecase.manageeducation.UpdateEducationCommand;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.AddExperienceCommand;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.ManageExperiencesService;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.RemoveExperienceCommand;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.UpdateExperienceCommand;
import com.usac.jobseeker.cv.application.usecase.manageprojects.AddProjectCommand;
import com.usac.jobseeker.cv.application.usecase.manageprojects.ManageProjectsService;
import com.usac.jobseeker.cv.application.usecase.manageprojects.RemoveProjectCommand;
import com.usac.jobseeker.cv.application.usecase.manageprojects.UpdateProjectCommand;
import com.usac.jobseeker.cv.domain.model.EducationLevel;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import com.usac.jobseeker.cv.domain.model.ProjectType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Una colección del perfil vista desde las pruebas: cómo añadir, reemplazar y quitar un elemento
 * válido o inválido, y cómo contarlos en el perfil que se devuelve. Las cuatro deben cumplir las
 * mismas reglas, y por eso una sola batería de pruebas las recorre a todas.
 */
public interface CollectionScenario {

  String useCase();

  /** El tipo de cambio que debe llevar el evento. */
  String changeKind();

  ProfessionalProfileView add(AccountId account, long version);

  /** Un alta con un dato que no cumple las reglas. */
  ProfessionalProfileView addInvalid(AccountId account, long version);

  ProfessionalProfileView update(AccountId account, long version, UUID itemId, String variant);

  ProfessionalProfileView remove(AccountId account, long version, UUID itemId);

  List<UUID> itemIds(ProfessionalProfileView view);

  /** Un texto representativo del elemento, para ver que se reemplazó. */
  String labelOf(ProfessionalProfileView view, UUID itemId);

  LocalDate D1 = LocalDate.of(2020, 1, 1);

  static CollectionScenario experiences(ProfileChange c, InMemoryItems items) {
    var s = new ManageExperiencesService(items.experiences, c);
    return new CollectionScenario() {
      public String useCase() {
        return "manage_experiences";
      }

      public String changeKind() {
        return "experience";
      }

      public ProfessionalProfileView add(AccountId a, long v) {
        return s.add(new AddExperienceCommand(a, v, "Acme", "Dev", D1, null, null));
      }

      public ProfessionalProfileView addInvalid(AccountId a, long v) {
        return s.add(new AddExperienceCommand(a, v, " ", "Dev", D1, null, null));
      }

      public ProfessionalProfileView update(AccountId a, long v, UUID id, String variant) {
        return s.update(new UpdateExperienceCommand(a, v, id, variant, "Dev", D1, null, null));
      }

      public ProfessionalProfileView remove(AccountId a, long v, UUID id) {
        return s.remove(new RemoveExperienceCommand(a, v, id));
      }

      public List<UUID> itemIds(ProfessionalProfileView v) {
        return v.collections().experiences().stream().map(e -> e.id().value()).toList();
      }

      public String labelOf(ProfessionalProfileView v, UUID id) {
        return v.collections().experiences().stream()
            .filter(e -> e.id().value().equals(id))
            .findFirst()
            .orElseThrow()
            .company();
      }
    };
  }

  static CollectionScenario education(ProfileChange c, InMemoryItems items) {
    var s = new ManageEducationService(items.education, c);
    return new CollectionScenario() {
      public String useCase() {
        return "manage_education";
      }

      public String changeKind() {
        return "education";
      }

      public ProfessionalProfileView add(AccountId a, long v) {
        return s.add(
            new AddEducationCommand(a, v, "USAC", "Ingeniería", EducationLevel.BACHELOR, null));
      }

      public ProfessionalProfileView addInvalid(AccountId a, long v) {
        return s.add(new AddEducationCommand(a, v, "", "Ingeniería", null, null));
      }

      public ProfessionalProfileView update(AccountId a, long v, UUID id, String variant) {
        return s.update(new UpdateEducationCommand(a, v, id, variant, "Ingeniería", null, null));
      }

      public ProfessionalProfileView remove(AccountId a, long v, UUID id) {
        return s.remove(new RemoveEducationCommand(a, v, id));
      }

      public List<UUID> itemIds(ProfessionalProfileView v) {
        return v.collections().education().stream().map(e -> e.id().value()).toList();
      }

      public String labelOf(ProfessionalProfileView v, UUID id) {
        return v.collections().education().stream()
            .filter(e -> e.id().value().equals(id))
            .findFirst()
            .orElseThrow()
            .institution();
      }
    };
  }

  static CollectionScenario certifications(ProfileChange c, InMemoryItems items) {
    var s = new ManageCertificationsService(items.certifications, c);
    return new CollectionScenario() {
      public String useCase() {
        return "manage_certifications";
      }

      public String changeKind() {
        return "certification";
      }

      public ProfessionalProfileView add(AccountId a, long v) {
        return s.add(
            new AddCertificationCommand(a, v, "AWS", "Amazon", D1, null, "https://example.test/c"));
      }

      public ProfessionalProfileView addInvalid(AccountId a, long v) {
        return s.add(
            new AddCertificationCommand(a, v, "AWS", "Amazon", D1, null, "javascript:alert(1)"));
      }

      public ProfessionalProfileView update(AccountId a, long v, UUID id, String variant) {
        return s.update(
            new UpdateCertificationCommand(a, v, id, variant, "Amazon", D1, null, null));
      }

      public ProfessionalProfileView remove(AccountId a, long v, UUID id) {
        return s.remove(new RemoveCertificationCommand(a, v, id));
      }

      public List<UUID> itemIds(ProfessionalProfileView v) {
        return v.collections().certifications().stream().map(e -> e.id().value()).toList();
      }

      public String labelOf(ProfessionalProfileView v, UUID id) {
        return v.collections().certifications().stream()
            .filter(e -> e.id().value().equals(id))
            .findFirst()
            .orElseThrow()
            .name();
      }
    };
  }

  static CollectionScenario projects(ProfileChange c, InMemoryItems items) {
    var s = new ManageProjectsService(items.projects, c);
    return new CollectionScenario() {
      public String useCase() {
        return "manage_projects";
      }

      public String changeKind() {
        return "project";
      }

      public ProfessionalProfileView add(AccountId a, long v) {
        return s.add(
            new AddProjectCommand(
                a, v, "JobSeeker", null, ProjectType.PERSONAL, null, D1, null, null));
      }

      public ProfessionalProfileView addInvalid(AccountId a, long v) {
        return s.add(new AddProjectCommand(a, v, "JobSeeker", null, null, null, D1, null, null));
      }

      public ProfessionalProfileView update(AccountId a, long v, UUID id, String variant) {
        return s.update(
            new UpdateProjectCommand(
                a, v, id, variant, null, ProjectType.WORK, null, D1, null, null));
      }

      public ProfessionalProfileView remove(AccountId a, long v, UUID id) {
        return s.remove(new RemoveProjectCommand(a, v, id));
      }

      public List<UUID> itemIds(ProfessionalProfileView v) {
        return v.collections().projects().stream().map(e -> e.id().value()).toList();
      }

      public String labelOf(ProfessionalProfileView v, UUID id) {
        return v.collections().projects().stream()
            .filter(e -> e.id().value().equals(id))
            .findFirst()
            .orElseThrow()
            .name();
      }
    };
  }

  static ProfileItemId idOf(UUID id) {
    return new ProfileItemId(id);
  }
}
