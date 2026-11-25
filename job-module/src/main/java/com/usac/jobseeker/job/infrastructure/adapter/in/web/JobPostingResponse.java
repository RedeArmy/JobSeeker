package com.usac.jobseeker.job.infrastructure.adapter.in.web;

import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobRequirement;
import com.usac.jobseeker.job.domain.model.JobStatus;
import com.usac.jobseeker.job.domain.model.RecruitingStage;
import com.usac.jobseeker.job.domain.model.SalaryRange;
import com.usac.jobseeker.job.domain.model.WorkModality;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Respuesta de una vacante: el contrato público, separado del modelo interno. Un candidato recibe
 * lo que le sirve para decidir si postula; los campos de gestión (versión, fechas, etapa de
 * reclutamiento) y quién la escribió van **solo** al dueño y llegan vacíos al resto (BR-VIS-03).
 */
@Schema(description = "Una vacante con sus requisitos.")
public record JobPostingResponse(
    @Schema(description = "Identificador de la vacante.") String id,
    @Schema(description = "`DRAFT`, `PUBLISHED`, `CLOSED`, `ARCHIVED` o `DELETED`.")
        JobStatus status,
    @Schema(description = "Título.") String title,
    @Schema(description = "Descripción.") String description,
    @Schema(description = "Modalidad de trabajo.") WorkModality modality,
    @Schema(description = "País, ISO 3166-1 alfa-2.", example = "GT") String country,
    @Schema(description = "Rango salarial con su moneda y periodo; ausente si no se dio.")
        Salary salary,
    @Schema(description = "Si recibe postulaciones; solo tiene efecto con la vacante publicada.")
        boolean acceptingApplications,
    @Schema(description = "Requisitos estructurados, en el orden en que se escribieron.")
        List<RequirementItem> requirements,
    @Schema(
            description =
                "Solo para el dueño: versión de la vacante. Debe devolverse al editarla o cambiar"
                    + " su estado.",
            example = "3")
        Long version,
    @Schema(description = "Solo para el dueño: etapa de reclutamiento.")
        RecruitingStage recruitingStage,
    @Schema(description = "Solo para el dueño: creación, en UTC (ISO-8601).") String createdAt,
    @Schema(description = "Solo para el dueño: última edición.") String updatedAt,
    @Schema(description = "Primera publicación, en UTC.") String publishedAt,
    @Schema(description = "Solo para el dueño: cierre más reciente.") String closedAt) {

  @Schema(description = "Rango salarial: moneda y periodo siempre van con él (BR-JOB-21).")
  public record Salary(
      @Schema(example = "3000") BigDecimal min,
      @Schema(example = "4500") BigDecimal max,
      @Schema(description = "Moneda ISO 4217.", example = "USD") String currency,
      @Schema(description = "`MONTH` o `YEAR`.") String period) {

    static Salary from(SalaryRange range) {
      return range == null
          ? null
          : new Salary(range.min(), range.max(), range.currency(), range.period().name());
    }
  }

  @Schema(description = "Un requisito estructurado; los campos ajenos a su tipo van vacíos.")
  public record RequirementItem(
      String type,
      boolean mandatory,
      String skillName,
      Integer minLevel,
      String educationLevel,
      Integer minYears,
      String language,
      String minProficiency) {

    static RequirementItem from(JobRequirement r) {
      return new RequirementItem(
          r.type().name(),
          r.mandatory(),
          r.skillName(),
          r.minLevel(),
          r.educationLevel() == null ? null : r.educationLevel().name(),
          r.minYears(),
          r.language(),
          r.minProficiency() == null ? null : r.minProficiency().name());
    }
  }

  public static JobPostingResponse from(JobPostingView view) {
    JobPosting p = view.posting();
    boolean owner = view.ownedByViewer();
    return new JobPostingResponse(
        p.id().toString(),
        p.status(),
        p.details().title(),
        p.details().description(),
        p.details().modality(),
        p.details().country(),
        Salary.from(p.details().salary()),
        p.acceptingApplications(),
        view.requirements().stream().map(RequirementItem::from).toList(),
        owner ? p.version() : null,
        owner ? p.recruitingStage() : null,
        owner ? iso(p.createdAt()) : null,
        owner ? iso(p.updatedAt()) : null,
        iso(p.publishedAt()),
        owner ? iso(p.closedAt()) : null);
  }

  private static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }
}
