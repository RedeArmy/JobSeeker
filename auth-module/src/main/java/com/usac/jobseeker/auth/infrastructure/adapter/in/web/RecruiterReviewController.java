package com.usac.jobseeker.auth.infrastructure.adapter.in.web;

import com.usac.jobseeker.auth.application.usecase.reviewrecruiter.ReviewRecruiterCommand;
import com.usac.jobseeker.auth.application.usecase.reviewrecruiter.ReviewRecruiterUseCase;
import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * El canal de verificación de reclutadores (BR-ACCT-11, TD-032). Delgado y sin lógica: traduce HTTP
 * al caso de uso. El operador sale del token, nunca de la ruta ni del cuerpo. Para quien no es
 * operador la ruta responde `404` igual que una que no existe: no se confirma que el canal exista.
 */
@RestController
@Tag(name = "auth", description = "Cuenta e identidad (módulo auth).")
public class RecruiterReviewController {

  private static final String ERROR_ENVELOPE = "#/components/schemas/ErrorEnvelope";

  private final ReviewRecruiterUseCase reviewRecruiter;

  public RecruiterReviewController(ReviewRecruiterUseCase reviewRecruiter) {
    this.reviewRecruiter = reviewRecruiter;
  }

  @PutMapping("/api/v1/admin/recruiters/{accountId}/verification")
  @Operation(
      summary = "Verificar, suspender o devolver a «sin verificar» a un reclutador",
      description =
          "Solo para operadores de la plataforma. Idempotente: repetir la decisión no cambia nada ni"
              + " deja rastro. Cada cambio queda en el registro de auditoría. Nadie puede decidir"
              + " sobre sí mismo. Para quien no es operador responde `404`, como si la ruta no"
              + " existiera.",
      security = @SecurityRequirement(name = "bearer"))
  @ApiResponse(responseCode = "200", description = "El estado en que quedó y si cambió.")
  @ApiResponse(
      responseCode = "400",
      description = "Falta el estado, no es válido, o el operador se apunta a sí mismo.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "401",
      description = "Falta el token, o no vale (firma, vigencia, audiencia o tipo).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "404",
      description = "Quien llama no es operador, o la cuenta no existe o no es reclutadora.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "429",
      description = "Se superó el límite de peticiones; reintentar tras `Retry-After`.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  public RecruiterReviewResponse review(
      Principal principal,
      @PathVariable String accountId,
      @RequestBody RecruiterReviewRequest body) {
    if (body == null || body.status() == null) {
      throw new ValidationFailedException(List.of(new FieldViolation("status", "REQUIRED")));
    }
    // `sub` ya fue validado como UUID por el decodificador de la cadena (`SubjectValidator`).
    AccountId operator = new AccountId(UUID.fromString(principal.getName()));
    boolean changed =
        reviewRecruiter.execute(
            new ReviewRecruiterCommand(operator, target(accountId), body.status()));
    return new RecruiterReviewResponse(body.status(), changed);
  }

  // Un identificador que no es un UUID no puede ser de una cuenta: se trata como inexistente.
  private static AccountId target(String raw) {
    try {
      return new AccountId(UUID.fromString(raw));
    } catch (IllegalArgumentException _) {
      throw new NotFoundException();
    }
  }
}
