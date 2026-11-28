package com.usac.jobseeker.ats.infrastructure.adapter.in.web;

import com.usac.jobseeker.ats.application.usecase.getapplication.GetApplicationQuery;
import com.usac.jobseeker.ats.application.usecase.getapplication.GetApplicationUseCase;
import com.usac.jobseeker.ats.application.usecase.listjobapplications.ListJobApplicationsQuery;
import com.usac.jobseeker.ats.application.usecase.listjobapplications.ListJobApplicationsUseCase;
import com.usac.jobseeker.ats.domain.model.ApplicationStatus;
import com.usac.jobseeker.ats.domain.model.PipelineStage;
import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El pipeline del reclutador (tdd/ats-tracker-modules.md §8). Delgado y sin lógica. Lo ajeno no
 * existe: una vacante o postulación de otro reclutador es `404`, no `403` (BR-VIS-03).
 */
@RestController
@Tag(name = "ats", description = "Postulaciones y pipeline del reclutador (módulo ats).")
public class PipelineController {

  private static final String ERROR_ENVELOPE = "#/components/schemas/ErrorEnvelope";

  private final ListJobApplicationsUseCase list;
  private final GetApplicationUseCase get;

  public PipelineController(ListJobApplicationsUseCase list, GetApplicationUseCase get) {
    this.list = list;
    this.get = get;
  }

  @GetMapping("/api/v1/ats/jobs/{id}/applications")
  @Operation(
      summary = "El pipeline de una vacante propia",
      description =
          "Las postulaciones con su puntaje, por puntaje (mayor primero; los pendientes al final) o"
              + " por fecha, filtrables por estado, columna y puntaje mínimo, y paginadas. El"
              + " puntaje es preliminar mientras `calibrated` sea falso (ADR-0030).",
      security = @SecurityRequirement(name = "bearer"))
  @ApiResponse(responseCode = "200", description = "Una página del pipeline.")
  @ApiResponse(
      responseCode = "400",
      description = "Un filtro, el orden o la paginación no son válidos (`VALIDATION_FAILED`).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "401",
      description = "Falta el token, o no vale.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "403",
      description = "Sin la capacidad de reclutador (`FORBIDDEN_CAPABILITY`).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "404",
      description = "La vacante no existe, está eliminada o es de otro reclutador.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "429",
      description = "Se superó el límite de peticiones; reintentar tras `Retry-After`.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  public PipelineResponses.Page listJobApplications(
      Principal principal,
      @PathVariable String id,
      @Parameter(description = "`ACTIVE`, `HIRED`, `REJECTED` o `WITHDRAWN`.")
          @RequestParam(required = false)
          String status,
      @Parameter(description = "`NEW`, `REVIEW`, `INTERVIEW` u `OFFER`.")
          @RequestParam(required = false)
          String stage,
      @Parameter(description = "De 0 a 100; deja fuera a los de puntaje pendiente.")
          @RequestParam(required = false)
          Double minScore,
      @Parameter(description = "`SCORE` (por defecto) o `DATE`.")
          @RequestParam(defaultValue = "SCORE")
          String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return PipelineResponses.Page.from(
        list.execute(
            new ListJobApplicationsQuery(
                caller(principal),
                uuidOrNotFound(id),
                option("status", status, ApplicationStatus.class),
                option("stage", stage, PipelineStage.class),
                minScore,
                required("sort", sort, ListJobApplicationsQuery.Sort.class),
                page,
                size)));
  }

  @GetMapping("/api/v1/ats/applications/{id}")
  @Operation(
      summary = "El detalle de una postulación",
      description =
          "Para el dueño de la vacante: el candidato con su contacto (REQ-C-07), el puntaje con su"
              + " desglose y el de postular, y el historial con los motivos de descarte.",
      security = @SecurityRequirement(name = "bearer"))
  @ApiResponse(responseCode = "200", description = "La postulación.")
  @ApiResponse(
      responseCode = "401",
      description = "Falta el token, o no vale.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "403",
      description = "Sin la capacidad de reclutador (`FORBIDDEN_CAPABILITY`).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "404",
      description = "No existe, o su vacante es de otro reclutador o está eliminada.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "429",
      description = "Se superó el límite de peticiones; reintentar tras `Retry-After`.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  public PipelineResponses.Detail getApplication(Principal principal, @PathVariable String id) {
    return PipelineResponses.Detail.from(
        get.execute(new GetApplicationQuery(caller(principal), uuidOrNotFound(id))));
  }

  private static <E extends Enum<E>> E option(String field, String raw, Class<E> type) {
    return raw == null || raw.isBlank() ? null : required(field, raw, type);
  }

  // Un valor fuera del catálogo es un error del cliente con su campo, no un 500.
  private static <E extends Enum<E>> E required(String field, String raw, Class<E> type) {
    try {
      return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException _) {
      throw new ValidationFailedException(List.of(new FieldViolation(field, "INVALID_VALUE")));
    }
  }

  // `sub` ya fue validado como UUID por el decodificador de la cadena (`SubjectValidator`).
  private static AccountId caller(Principal principal) {
    return new AccountId(UUID.fromString(principal.getName()));
  }

  private static UUID uuidOrNotFound(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException _) {
      throw new NotFoundException();
    }
  }
}
