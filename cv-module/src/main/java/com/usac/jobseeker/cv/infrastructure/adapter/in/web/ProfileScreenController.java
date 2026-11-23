package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.GetMyProfessionalProfileQuery;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.GetMyProfessionalProfileUseCase;
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
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.SaveProfessionalProfileCommand;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.SaveProfessionalProfileUseCase;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * La pantalla del perfil profesional (Thymeleaf + HTMX, ADR-0005). Es el mismo trabajo que la API
 * JSON de `ProfessionalProfileController` y `ProfileCollectionsController`, con las mismas reglas,
 * porque ambas llaman a los mismos casos de uso: lo único propio de aquí es devolver HTML.
 *
 * <p>Cada operación devuelve el fragmento `content` con el perfil completo y su versión nueva, que
 * HTMX pone en lugar del anterior; así el formulario siguiente ya lleva la versión vigente. Un
 * error de validación **no** reemplaza el contenido, sino solo la zona de errores: la persona no
 * pierde lo que acaba de teclear.
 *
 * <p>La identidad sale de la sesión (`Principal`), nunca de un campo del formulario. Toda ruta que
 * muta exige el token CSRF, que la cadena web comprueba antes de llegar aquí (ADR-0020). Los
 * manejadores de excepción de esta clase solo aplican a esta pantalla; la API conserva el suyo.
 */
@Controller
public class ProfileScreenController {

  private static final String PAGE_VIEW = "cv/profile";
  private static final String CONTENT_FRAGMENT = "cv/profile :: content";
  private static final String ERRORS_FRAGMENT = "cv/profile :: errors";
  private static final String BASE = "/app/profile";
  // El nombre con el que la plantilla recibe el perfil.
  private static final String PROFILE_ATTRIBUTE = "profile";

  private final GetMyProfessionalProfileUseCase getProfile;
  private final SaveProfessionalProfileUseCase saveProfile;
  private final ManageExperiencesUseCase experiences;
  private final ManageEducationUseCase education;
  private final ManageCertificationsUseCase certifications;
  private final ManageProjectsUseCase projects;

  public ProfileScreenController(
      GetMyProfessionalProfileUseCase getProfile,
      SaveProfessionalProfileUseCase saveProfile,
      ManageExperiencesUseCase experiences,
      ManageEducationUseCase education,
      ManageCertificationsUseCase certifications,
      ManageProjectsUseCase projects) {
    this.getProfile = getProfile;
    this.saveProfile = saveProfile;
    this.experiences = experiences;
    this.education = education;
    this.certifications = certifications;
    this.projects = projects;
  }

  @GetMapping(BASE)
  String page(Principal principal, Model model) {
    model.addAttribute(PROFILE_ATTRIBUTE, currentProfile(principal));
    return PAGE_VIEW;
  }

  // ---- identidad ----

  @PutMapping(BASE + "/identity")
  String saveIdentity(
      Principal principal, Model model, @ModelAttribute SaveProfessionalProfileRequest form) {
    var result =
        saveProfile.execute(
            new SaveProfessionalProfileCommand(
                caller(principal),
                form.fullName(),
                form.headline(),
                form.bio(),
                version(form.version())));
    return content(model, result.profile());
  }

  // ---- experiencia ----

  @PostMapping(BASE + "/experiences")
  String addExperience(Principal principal, Model model, @ModelAttribute ExperienceRequest f) {
    return content(
        model,
        experiences.add(
            new AddExperienceCommand(
                caller(principal),
                version(f.version()),
                f.company(),
                f.title(),
                f.startDate(),
                f.endDate(),
                f.description())));
  }

  @PutMapping(BASE + "/experiences/{id}")
  String updateExperience(
      Principal principal,
      Model model,
      @PathVariable UUID id,
      @ModelAttribute ExperienceRequest f) {
    return content(
        model,
        experiences.update(
            new UpdateExperienceCommand(
                caller(principal),
                version(f.version()),
                id,
                f.company(),
                f.title(),
                f.startDate(),
                f.endDate(),
                f.description())));
  }

  @DeleteMapping(BASE + "/experiences/{id}")
  String removeExperience(
      Principal principal, Model model, @PathVariable UUID id, @RequestParam long version) {
    return content(
        model, experiences.remove(new RemoveExperienceCommand(caller(principal), version, id)));
  }

  // ---- formación ----

  @PostMapping(BASE + "/education")
  String addEducation(Principal principal, Model model, @ModelAttribute EducationRequest f) {
    return content(
        model,
        education.add(
            new AddEducationCommand(
                caller(principal),
                version(f.version()),
                f.institution(),
                f.degree(),
                f.level(),
                f.completionDate())));
  }

  @PutMapping(BASE + "/education/{id}")
  String updateEducation(
      Principal principal, Model model, @PathVariable UUID id, @ModelAttribute EducationRequest f) {
    return content(
        model,
        education.update(
            new UpdateEducationCommand(
                caller(principal),
                version(f.version()),
                id,
                f.institution(),
                f.degree(),
                f.level(),
                f.completionDate())));
  }

  @DeleteMapping(BASE + "/education/{id}")
  String removeEducation(
      Principal principal, Model model, @PathVariable UUID id, @RequestParam long version) {
    return content(
        model, education.remove(new RemoveEducationCommand(caller(principal), version, id)));
  }

  // ---- certificaciones ----

  @PostMapping(BASE + "/certifications")
  String addCertification(
      Principal principal, Model model, @ModelAttribute CertificationRequest f) {
    return content(
        model,
        certifications.add(
            new AddCertificationCommand(
                caller(principal),
                version(f.version()),
                f.name(),
                f.issuingOrganization(),
                f.issueDate(),
                f.expirationDate(),
                f.credentialUrl())));
  }

  @PutMapping(BASE + "/certifications/{id}")
  String updateCertification(
      Principal principal,
      Model model,
      @PathVariable UUID id,
      @ModelAttribute CertificationRequest f) {
    return content(
        model,
        certifications.update(
            new UpdateCertificationCommand(
                caller(principal),
                version(f.version()),
                id,
                f.name(),
                f.issuingOrganization(),
                f.issueDate(),
                f.expirationDate(),
                f.credentialUrl())));
  }

  @DeleteMapping(BASE + "/certifications/{id}")
  String removeCertification(
      Principal principal, Model model, @PathVariable UUID id, @RequestParam long version) {
    return content(
        model,
        certifications.remove(new RemoveCertificationCommand(caller(principal), version, id)));
  }

  // ---- proyectos ----

  @PostMapping(BASE + "/projects")
  String addProject(Principal principal, Model model, @ModelAttribute ProjectRequest f) {
    return content(
        model,
        projects.add(
            new AddProjectCommand(
                caller(principal),
                version(f.version()),
                f.name(),
                f.description(),
                f.type(),
                f.role(),
                f.startDate(),
                f.endDate(),
                f.url())));
  }

  @PutMapping(BASE + "/projects/{id}")
  String updateProject(
      Principal principal, Model model, @PathVariable UUID id, @ModelAttribute ProjectRequest f) {
    return content(
        model,
        projects.update(
            new UpdateProjectCommand(
                caller(principal),
                version(f.version()),
                id,
                f.name(),
                f.description(),
                f.type(),
                f.role(),
                f.startDate(),
                f.endDate(),
                f.url())));
  }

  @DeleteMapping(BASE + "/projects/{id}")
  String removeProject(
      Principal principal, Model model, @PathVariable UUID id, @RequestParam long version) {
    return content(
        model, projects.remove(new RemoveProjectCommand(caller(principal), version, id)));
  }

  // ---- errores: HTML en vez del sobre JSON de la API ----

  /** 422 y solo la zona de errores: lo tecleado en los formularios queda como estaba. */
  @ExceptionHandler(ValidationFailedException.class)
  @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
  String invalid(ValidationFailedException failure, Model model) {
    model.addAttribute("errors", ProfileMessages.describe(failure.violations()));
    return ERRORS_FRAGMENT;
  }

  /** 409 y el contenido recargado: quien editó con una versión vieja necesita la vigente. */
  @ExceptionHandler(StaleStateException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  String stale(Principal principal, Model model) {
    model.addAttribute(PROFILE_ATTRIBUTE, currentProfile(principal));
    model.addAttribute(
        "notice",
        "El perfil cambió desde que lo abriste, en otra pestaña o dispositivo. Lo recargamos: revisa y repite tu cambio.");
    return CONTENT_FRAGMENT;
  }

  /** 404 y el contenido recargado: el elemento ya no existe o nunca fue suyo (no se distingue). */
  @ExceptionHandler(NotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  String notFound(Principal principal, Model model) {
    model.addAttribute(PROFILE_ATTRIBUTE, currentProfile(principal));
    model.addAttribute("notice", "Ese elemento ya no existe. Recargamos tu perfil.");
    return CONTENT_FRAGMENT;
  }

  // ---- auxiliares ----

  private String content(Model model, ProfessionalProfileView view) {
    model.addAttribute(PROFILE_ATTRIBUTE, ProfessionalProfileResponse.from(view));
    return CONTENT_FRAGMENT;
  }

  private ProfessionalProfileResponse currentProfile(Principal principal) {
    return ProfessionalProfileResponse.from(
        getProfile.execute(new GetMyProfessionalProfileQuery(caller(principal))));
  }

  // La versión es obligatoria en toda escritura: sin ella no hay forma de saber qué se leyó.
  private static long version(Long value) {
    if (value == null) {
      throw new ValidationFailedException(List.of(new FieldViolation("version", "REQUIRED")));
    }
    return value;
  }

  // `sub` ya fue validado como UUID por el decodificador de la cadena.
  private static AccountId caller(Principal principal) {
    return new AccountId(UUID.fromString(principal.getName()));
  }
}
