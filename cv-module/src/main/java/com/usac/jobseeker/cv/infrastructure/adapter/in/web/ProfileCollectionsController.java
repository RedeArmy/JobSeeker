package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las cuatro colecciones del perfil profesional de la propia cuenta: experiencia, formación,
 * certificaciones y proyectos. Delgado y sin lógica: traduce HTTP al caso de uso y su resultado al
 * contrato. La identidad sale del token, nunca de la ruta ni del cuerpo; un elemento de otro perfil
 * responde igual que uno inexistente (BR-ACCT-04).
 *
 * <p>Toda operación devuelve el **perfil completo** con su versión nueva, así el cliente nunca
 * queda con una versión vieja para la siguiente edición. El límite de tasa, el sobre de error y las
 * métricas HTTP son transversales y no se repiten aquí.
 */
@RestController
@Tag(name = "cv", description = "Perfil profesional y de candidato (módulo cv).")
public class ProfileCollectionsController {

  private static final String PATH = "/api/v1/me/professional-profile";

  private final ManageExperiencesUseCase experiences;
  private final ManageEducationUseCase education;
  private final ManageCertificationsUseCase certifications;
  private final ManageProjectsUseCase projects;

  public ProfileCollectionsController(
      ManageExperiencesUseCase experiences,
      ManageEducationUseCase education,
      ManageCertificationsUseCase certifications,
      ManageProjectsUseCase projects) {
    this.experiences = experiences;
    this.education = education;
    this.certifications = certifications;
    this.projects = projects;
  }

  // ---- experiencia laboral ----

  @PostMapping(PATH + "/experiences")
  @Operation(
      summary = "Añadir una experiencia laboral",
      description = ApiDocs.ADD_DESCRIPTION,
      security = @SecurityRequirement(name = ApiDocs.BEARER))
  @ApiResponse(responseCode = ApiDocs.CREATED, description = ApiDocs.ADDED)
  @ProfileWriteResponses
  public ResponseEntity<ProfessionalProfileResponse> addExperience(
      Principal principal, @RequestBody ExperienceRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            respond(
                experiences.add(
                    new AddExperienceCommand(
                        caller(principal),
                        version(body.version()),
                        body.company(),
                        body.title(),
                        body.startDate(),
                        body.endDate(),
                        body.description()))));
  }

  @PutMapping(PATH + "/experiences/{id}")
  @Operation(
      summary = "Reemplazar una experiencia laboral",
      description = ApiDocs.REPLACE_DESCRIPTION,
      security = @SecurityRequirement(name = ApiDocs.BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = ApiDocs.REPLACED)
  @ProfileWriteResponses
  public ProfessionalProfileResponse updateExperience(
      Principal principal, @PathVariable UUID id, @RequestBody ExperienceRequest body) {
    return respond(
        experiences.update(
            new UpdateExperienceCommand(
                caller(principal),
                version(body.version()),
                id,
                body.company(),
                body.title(),
                body.startDate(),
                body.endDate(),
                body.description())));
  }

  @DeleteMapping(PATH + "/experiences/{id}")
  @Operation(
      summary = "Quitar una experiencia laboral",
      description = ApiDocs.REMOVE_DESCRIPTION,
      security = @SecurityRequirement(name = ApiDocs.BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = ApiDocs.REMOVED)
  @ProfileWriteResponses
  public ProfessionalProfileResponse removeExperience(
      Principal principal, @PathVariable UUID id, @RequestParam long version) {
    return respond(experiences.remove(new RemoveExperienceCommand(caller(principal), version, id)));
  }

  // ---- formación académica ----

  @PostMapping(PATH + "/education")
  @Operation(
      summary = "Añadir una formación académica",
      description = ApiDocs.ADD_DESCRIPTION,
      security = @SecurityRequirement(name = ApiDocs.BEARER))
  @ApiResponse(responseCode = ApiDocs.CREATED, description = ApiDocs.ADDED)
  @ProfileWriteResponses
  public ResponseEntity<ProfessionalProfileResponse> addEducation(
      Principal principal, @RequestBody EducationRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            respond(
                education.add(
                    new AddEducationCommand(
                        caller(principal),
                        version(body.version()),
                        body.institution(),
                        body.degree(),
                        body.level(),
                        body.completionDate()))));
  }

  @PutMapping(PATH + "/education/{id}")
  @Operation(
      summary = "Reemplazar una formación académica",
      description = ApiDocs.REPLACE_DESCRIPTION,
      security = @SecurityRequirement(name = ApiDocs.BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = ApiDocs.REPLACED)
  @ProfileWriteResponses
  public ProfessionalProfileResponse updateEducation(
      Principal principal, @PathVariable UUID id, @RequestBody EducationRequest body) {
    return respond(
        education.update(
            new UpdateEducationCommand(
                caller(principal),
                version(body.version()),
                id,
                body.institution(),
                body.degree(),
                body.level(),
                body.completionDate())));
  }

  @DeleteMapping(PATH + "/education/{id}")
  @Operation(
      summary = "Quitar una formación académica",
      description = ApiDocs.REMOVE_DESCRIPTION,
      security = @SecurityRequirement(name = ApiDocs.BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = ApiDocs.REMOVED)
  @ProfileWriteResponses
  public ProfessionalProfileResponse removeEducation(
      Principal principal, @PathVariable UUID id, @RequestParam long version) {
    return respond(education.remove(new RemoveEducationCommand(caller(principal), version, id)));
  }

  // ---- certificación ----

  @PostMapping(PATH + "/certifications")
  @Operation(
      summary = "Añadir una certificación",
      description = ApiDocs.ADD_DESCRIPTION,
      security = @SecurityRequirement(name = ApiDocs.BEARER))
  @ApiResponse(responseCode = ApiDocs.CREATED, description = ApiDocs.ADDED)
  @ProfileWriteResponses
  public ResponseEntity<ProfessionalProfileResponse> addCertification(
      Principal principal, @RequestBody CertificationRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            respond(
                certifications.add(
                    new AddCertificationCommand(
                        caller(principal),
                        version(body.version()),
                        body.name(),
                        body.issuingOrganization(),
                        body.issueDate(),
                        body.expirationDate(),
                        body.credentialUrl()))));
  }

  @PutMapping(PATH + "/certifications/{id}")
  @Operation(
      summary = "Reemplazar una certificación",
      description = ApiDocs.REPLACE_DESCRIPTION,
      security = @SecurityRequirement(name = ApiDocs.BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = ApiDocs.REPLACED)
  @ProfileWriteResponses
  public ProfessionalProfileResponse updateCertification(
      Principal principal, @PathVariable UUID id, @RequestBody CertificationRequest body) {
    return respond(
        certifications.update(
            new UpdateCertificationCommand(
                caller(principal),
                version(body.version()),
                id,
                body.name(),
                body.issuingOrganization(),
                body.issueDate(),
                body.expirationDate(),
                body.credentialUrl())));
  }

  @DeleteMapping(PATH + "/certifications/{id}")
  @Operation(
      summary = "Quitar una certificación",
      description = ApiDocs.REMOVE_DESCRIPTION,
      security = @SecurityRequirement(name = ApiDocs.BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = ApiDocs.REMOVED)
  @ProfileWriteResponses
  public ProfessionalProfileResponse removeCertification(
      Principal principal, @PathVariable UUID id, @RequestParam long version) {
    return respond(
        certifications.remove(new RemoveCertificationCommand(caller(principal), version, id)));
  }

  // ---- proyecto ----

  @PostMapping(PATH + "/projects")
  @Operation(
      summary = "Añadir un proyecto",
      description = ApiDocs.ADD_DESCRIPTION,
      security = @SecurityRequirement(name = ApiDocs.BEARER))
  @ApiResponse(responseCode = ApiDocs.CREATED, description = ApiDocs.ADDED)
  @ProfileWriteResponses
  public ResponseEntity<ProfessionalProfileResponse> addProject(
      Principal principal, @RequestBody ProjectRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            respond(
                projects.add(
                    new AddProjectCommand(
                        caller(principal),
                        version(body.version()),
                        body.name(),
                        body.description(),
                        body.type(),
                        body.role(),
                        body.startDate(),
                        body.endDate(),
                        body.url()))));
  }

  @PutMapping(PATH + "/projects/{id}")
  @Operation(
      summary = "Reemplazar un proyecto",
      description = ApiDocs.REPLACE_DESCRIPTION,
      security = @SecurityRequirement(name = ApiDocs.BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = ApiDocs.REPLACED)
  @ProfileWriteResponses
  public ProfessionalProfileResponse updateProject(
      Principal principal, @PathVariable UUID id, @RequestBody ProjectRequest body) {
    return respond(
        projects.update(
            new UpdateProjectCommand(
                caller(principal),
                version(body.version()),
                id,
                body.name(),
                body.description(),
                body.type(),
                body.role(),
                body.startDate(),
                body.endDate(),
                body.url())));
  }

  @DeleteMapping(PATH + "/projects/{id}")
  @Operation(
      summary = "Quitar un proyecto",
      description = ApiDocs.REMOVE_DESCRIPTION,
      security = @SecurityRequirement(name = ApiDocs.BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = ApiDocs.REMOVED)
  @ProfileWriteResponses
  public ProfessionalProfileResponse removeProject(
      Principal principal, @PathVariable UUID id, @RequestParam long version) {
    return respond(projects.remove(new RemoveProjectCommand(caller(principal), version, id)));
  }

  private static ProfessionalProfileResponse respond(ProfessionalProfileView view) {
    return ProfessionalProfileResponse.from(view);
  }

  // La versión es obligatoria en toda escritura: sin ella no hay forma de saber qué se leyó.
  private static long version(Long value) {
    if (value == null) {
      throw new ValidationFailedException(List.of(new FieldViolation("version", "REQUIRED")));
    }
    return value;
  }

  // `sub` ya fue validado como UUID por el decodificador de la cadena (`SubjectValidator`).
  private static AccountId caller(Principal principal) {
    return new AccountId(UUID.fromString(principal.getName()));
  }
}
