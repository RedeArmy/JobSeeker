package com.usac.jobseeker.matching.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestCandidatesForJobQuery;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestCandidatesForJobUseCase;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.infrastructure.config.MatchingProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los candidatos sugeridos de una vacante propia (BR-MATCH-06). Delgado y sin lógica: el reclutador
 * sale del token, nunca de la ruta ni de un parámetro, y el control de acceso, la cuota y el
 * registro de lo mostrado son del caso de uso. Es la superficie más sensible contra el raspado de
 * datos de candidatos (amenaza T-N1), por eso tiene su propia política de límite de tasa, más
 * estricta que la de lectura (ADR-0011).
 */
@RestController
@Tag(name = "matching", description = "Compatibilidad CV-vacante (módulo matching).")
public class SuggestionsController {

  private static final String ERROR_ENVELOPE = "#/components/schemas/ErrorEnvelope";
  private static final int DEFAULT_SIZE = 20;

  private final SuggestCandidatesForJobUseCase suggestCandidates;
  private final int maxPageSize;

  public SuggestionsController(
      SuggestCandidatesForJobUseCase suggestions, MatchingProperties properties) {
    this.suggestCandidates = suggestions;
    this.maxPageSize = properties.suggestions().maxPageSize();
  }

  @GetMapping("/api/v1/matching/jobs/{jobId}/suggestions")
  @Operation(
      summary = "Candidatos sugeridos para una vacante propia",
      description =
          "Solo para reclutadores **verificados** (BR-ACCT-11) y dueños de la vacante, que debe estar"
              + " publicada. Cuenta como visto cada perfil **único** que se muestra, con un máximo por"
              + " ventana de 24 h; volver a ver uno ya visto no gasta cuota. Devuelve identificadores"
              + " opacos en orden de cercanía, sin similitud ni datos del candidato. Una vacante en"
              + " `REVIEWING` devuelve `PAUSED` y una sin vector, `PENDING_EMBEDDING`, ambas sin"
              + " candidatos.",
      security = @SecurityRequirement(name = "bearer"))
  @ApiResponse(responseCode = "200", description = "Los sugeridos, o el motivo por el que no hay.")
  @ApiResponse(
      responseCode = "400",
      description = "`size` fuera de rango.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "401",
      description = "Falta el token, o no vale (firma, vigencia, audiencia o tipo).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "403",
      description =
          "`RECRUITER_NOT_VERIFIED`, `RECRUITER_SUSPENDED` o `FORBIDDEN_CAPABILITY` si la cuenta no es"
              + " reclutadora.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "404",
      description =
          "La vacante no existe, está eliminada o es de otro reclutador: no se distingue.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "409",
      description = "`INVALID_STATE`: la vacante no está publicada.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "429",
      description =
          "`QUOTA_EXCEEDED` (perfiles únicos de la ventana agotados) o límite de peticiones;"
              + " reintentar más tarde.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  public SuggestionsResponse suggestions(
      Principal principal,
      @PathVariable String jobId,
      @RequestParam(name = "size", required = false) Integer size) {
    int pageSize = size == null ? Math.min(DEFAULT_SIZE, maxPageSize) : size;
    if (pageSize < 1 || pageSize > maxPageSize) {
      throw new ValidationFailedException(List.of(new FieldViolation("size", "OUT_OF_RANGE")));
    }
    // `sub` ya fue validado como UUID por el decodificador de la cadena (`SubjectValidator`).
    AccountId recruiter = new AccountId(UUID.fromString(principal.getName()));
    return SuggestionsResponse.from(
        suggestCandidates.execute(
            new SuggestCandidatesForJobQuery(recruiter, job(jobId), pageSize)));
  }

  // Un identificador que no es un UUID no puede ser de una vacante: es inexistente, no inválido.
  private static JobPostingId job(String raw) {
    try {
      return new JobPostingId(UUID.fromString(raw));
    } catch (IllegalArgumentException _) {
      throw new NotFoundException();
    }
  }
}
