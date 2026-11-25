package com.usac.jobseeker.job.infrastructure.adapter.in.web;

import com.usac.jobseeker.job.domain.model.EducationLevel;
import com.usac.jobseeker.job.domain.model.JobStatus;
import com.usac.jobseeker.job.domain.model.LanguageProficiency;
import com.usac.jobseeker.job.domain.model.RecruitingStage;
import com.usac.jobseeker.job.domain.model.RequirementType;
import com.usac.jobseeker.job.domain.model.SalaryPeriod;
import com.usac.jobseeker.job.domain.model.WorkModality;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/** Los cuerpos de las operaciones sobre una vacante. */
interface JobRequests {

  @Schema(description = "Los datos de la vacante (BR-JOB-02, BR-JOB-21).")
  record Posting(
      @Schema(description = "Título, una sola línea, hasta 150 caracteres.", example = "Backend")
          String title,
      @Schema(description = "Descripción, hasta 10 000 caracteres.") String description,
      @Schema(description = "Modalidad de trabajo.") WorkModality modality,
      @Schema(description = "País, ISO 3166-1 alfa-2.", example = "GT") String country,
      @Schema(description = "Salario mínimo; con moneda y periodo.", example = "3000")
          BigDecimal salaryMin,
      @Schema(description = "Salario máximo; con moneda y periodo.", example = "4500")
          BigDecimal salaryMax,
      @Schema(description = "Moneda ISO 4217.", example = "USD") String salaryCurrency,
      @Schema(description = "Periodo del importe.") SalaryPeriod salaryPeriod,
      @Schema(
              description =
                  "Solo al editar: " + ApiDocs.VERSION_DESCRIPTION + " Se ignora al crear.",
              example = "1")
          Long version) {}

  @Schema(description = "Un requisito estructurado; los campos que no son de su tipo se omiten.")
  record Requirement(
      @Schema(description = "`SKILL`, `EDUCATION`, `EXPERIENCE_YEARS` o `LANGUAGE`.")
          RequirementType type,
      @Schema(description = "Obligatorio (`true`) o deseable (por defecto).") Boolean mandatory,
      @Schema(description = "SKILL: nombre de la habilidad.", example = "Java") String skillName,
      @Schema(description = "SKILL: nivel mínimo de 1 (básico) a 4 (experto), opcional.")
          Integer minLevel,
      @Schema(description = "EDUCATION: nivel académico mínimo.") EducationLevel educationLevel,
      @Schema(description = "EXPERIENCE_YEARS: años mínimos, de 1 a 60.") Integer minYears,
      @Schema(description = "LANGUAGE: código ISO 639-1.", example = "en") String language,
      @Schema(description = "LANGUAGE: nivel mínimo.") LanguageProficiency minProficiency) {}

  @Schema(description = "El conjunto completo de requisitos que debe quedar.")
  record Requirements(
      @Schema(description = "Hasta 30; reemplaza el conjunto actual.")
          List<Requirement> requirements,
      @Schema(description = ApiDocs.VERSION_DESCRIPTION, example = "2") Long version) {}

  @Schema(description = "Pedir una transición manual del ciclo de vida (BR-JOB-03).")
  record Transition(
      @Schema(
              description =
                  "`PUBLISHED`, `CLOSED` o `DELETED`. Archivar no se pide: es automático"
                      + " (BR-JOB-14).")
          JobStatus to,
      @Schema(description = ApiDocs.VERSION_DESCRIPTION, example = "2") Long version) {}

  @Schema(description = "Pausar o reanudar la recepción de postulaciones (BR-JOB-10).")
  record AcceptingApplications(
      @Schema(description = "Obligatorio: `false` pausa, `true` reanuda.") Boolean accepting,
      @Schema(description = ApiDocs.VERSION_DESCRIPTION, example = "2") Long version) {}

  @Schema(description = "Cambiar la etapa de reclutamiento (BR-JOB-04).")
  record Stage(
      @Schema(description = "`HIRING` o `REVIEWING` (pausa los sugeridos, BR-MATCH-08).")
          RecruitingStage stage,
      @Schema(description = ApiDocs.VERSION_DESCRIPTION, example = "2") Long version) {}
}
