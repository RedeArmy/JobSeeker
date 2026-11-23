package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.domain.model.Certification;
import com.usac.jobseeker.cv.domain.model.Education;
import com.usac.jobseeker.cv.domain.model.EducationLevel;
import com.usac.jobseeker.cv.domain.model.Experience;
import com.usac.jobseeker.cv.domain.model.Project;
import com.usac.jobseeker.cv.domain.model.ProjectType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Respuesta del perfil profesional: el contrato público, separado del modelo interno. */
@Schema(description = "El perfil profesional de la cuenta, o el estado «aún sin crear».")
public record ProfessionalProfileResponse(
    @Schema(
            description =
                "`false` si la cuenta todavía no creó su perfil: los demás campos vienen vacíos y"
                    + " `version` es 0, que es lo que se envía al crearlo.")
        boolean created,
    @Schema(description = "Identificador del perfil; vacío si aún no existe.") String id,
    @Schema(description = "Nombre completo.", example = "Ana María López") String fullName,
    @Schema(description = "Titular profesional.", example = "Backend Engineer") String headline,
    @Schema(description = "Biografía; admite varias líneas.") String bio,
    @Schema(
            description =
                "Versión del perfil. Debe devolverse al guardar: si otra edición se guardó antes, la"
                    + " petición se rechaza con `409 STALE_STATE` en vez de pisarla.",
            example = "3")
        long version,
    @Schema(description = "Última edición, en UTC (ISO-8601).", example = "2026-11-23T16:30:00Z")
        String updatedAt,
    @Schema(description = "Experiencia laboral, la vigente primero.")
        List<ExperienceItem> experiences,
    @Schema(description = "Formación académica, la en curso primero.")
        List<EducationItem> education,
    @Schema(description = "Certificaciones, la más reciente primero.")
        List<CertificationItem> certifications,
    @Schema(description = "Proyectos laborales y personales, el en curso primero.")
        List<ProjectItem> projects) {

  @Schema(description = "Una experiencia laboral. Sin `endDate` es la actual.")
  public record ExperienceItem(
      String id,
      String company,
      String title,
      LocalDate startDate,
      LocalDate endDate,
      String description) {

    static ExperienceItem from(Experience e) {
      return new ExperienceItem(
          e.id().toString(), e.company(), e.title(), e.startDate(), e.endDate(), e.description());
    }
  }

  @Schema(description = "Una formación académica. Sin `completionDate` está en curso.")
  public record EducationItem(
      String id,
      String institution,
      String degree,
      EducationLevel level,
      LocalDate completionDate) {

    static EducationItem from(Education e) {
      return new EducationItem(
          e.id().toString(), e.institution(), e.degree(), e.level(), e.completionDate());
    }
  }

  @Schema(description = "Una certificación. Sin `expirationDate` no expira.")
  public record CertificationItem(
      String id,
      String name,
      String issuingOrganization,
      LocalDate issueDate,
      LocalDate expirationDate,
      String credentialUrl) {

    static CertificationItem from(Certification c) {
      return new CertificationItem(
          c.id().toString(),
          c.name(),
          c.issuingOrganization(),
          c.issueDate(),
          c.expirationDate(),
          c.credentialUrl());
    }
  }

  @Schema(description = "Un proyecto laboral o personal. Sin `endDate` está en curso.")
  public record ProjectItem(
      String id,
      String name,
      String description,
      ProjectType type,
      String role,
      LocalDate startDate,
      LocalDate endDate,
      String url) {

    static ProjectItem from(Project p) {
      return new ProjectItem(
          p.id().toString(),
          p.name(),
          p.description(),
          p.type(),
          p.role(),
          p.startDate(),
          p.endDate(),
          p.url());
    }
  }

  /** El perfil aún no existe: lo único que el cliente necesita es saberlo y la versión inicial. */
  static ProfessionalProfileResponse notCreated() {
    return new ProfessionalProfileResponse(
        false, null, null, null, null, 0, null, List.of(), List.of(), List.of(), List.of());
  }

  static ProfessionalProfileResponse from(ProfessionalProfileView view) {
    return new ProfessionalProfileResponse(
        true,
        view.id().toString(),
        view.fullName(),
        view.headline(),
        view.bio(),
        view.version(),
        view.updatedAt().toString(),
        view.collections().experiences().stream().map(ExperienceItem::from).toList(),
        view.collections().education().stream().map(EducationItem::from).toList(),
        view.collections().certifications().stream().map(CertificationItem::from).toList(),
        view.collections().projects().stream().map(ProjectItem::from).toList());
  }

  static ProfessionalProfileResponse from(Optional<ProfessionalProfileView> view) {
    return view.map(ProfessionalProfileResponse::from)
        .orElseGet(ProfessionalProfileResponse::notCreated);
  }
}
