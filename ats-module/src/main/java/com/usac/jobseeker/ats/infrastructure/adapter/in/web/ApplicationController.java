package com.usac.jobseeker.ats.infrastructure.adapter.in.web;

import com.usac.jobseeker.ats.application.usecase.submitapplication.SubmitApplicationCommand;
import com.usac.jobseeker.ats.application.usecase.submitapplication.SubmitApplicationUseCase;
import com.usac.jobseeker.ats.application.usecase.withdrawapplication.WithdrawApplicationCommand;
import com.usac.jobseeker.ats.application.usecase.withdrawapplication.WithdrawApplicationUseCase;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.model.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las postulaciones (tdd/ats-tracker-modules.md §8). Delgado y sin lógica: traduce HTTP al caso de
 * uso y su resultado al contrato. El candidato sale del token, nunca de la ruta ni del cuerpo. El
 * límite de tasa (política de escritura), el sobre de error y las métricas HTTP son transversales.
 */
@RestController
@Tag(name = "ats", description = "Postulaciones y pipeline del reclutador (módulo ats).")
public class ApplicationController {

  private static final String ERROR_ENVELOPE = "#/components/schemas/ErrorEnvelope";

  private final SubmitApplicationUseCase submit;
  private final WithdrawApplicationUseCase withdraw;

  public ApplicationController(
      SubmitApplicationUseCase submit, WithdrawApplicationUseCase withdraw) {
    this.submit = submit;
    this.withdraw = withdraw;
  }

  @PostMapping("/api/v1/ats/jobs/{id}/applications")
  @Operation(
      summary = "Postular a una vacante",
      description =
          "Crea la postulación en proceso, en la columna `NEW`, con el CV vigente del candidato. Exige"
              + " la capacidad de candidato, nombre, contacto y un CV cargado (BR-CAND-01). Con"
              + " `Idempotency-Key`, repetir el envío devuelve la misma postulación en vez de"
              + " crear otra.",
      security = @SecurityRequirement(name = "bearer"))
  @ApiResponse(responseCode = "201", description = "Postulación creada (o la misma, si se repite).")
  @ApiResponse(
      responseCode = "400",
      description = "`Idempotency-Key` con un formato no válido (`VALIDATION_FAILED`).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "401",
      description = "Falta el token, o no vale.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "403",
      description =
          "Sin la capacidad de candidato (`FORBIDDEN_CAPABILITY`), o la vacante es propia"
              + " (`SELF_APPLICATION`, BR-ACCT-05).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "404",
      description = "La vacante no existe o no es visible para candidatos (BR-JOB-11).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "409",
      description =
          "`ALREADY_APPLIED` si ya hay una postulación sin retirar a esta vacante (BR-TRACK-01,"
              + " BR-TRACK-05); `INVALID_STATE` si la vacante está cerrada o en pausa (BR-JOB-10).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "422",
      description =
          "`APPLICATION_REQUIREMENTS_MISSING`, con `FULL_NAME`, `CONTACT` o `CV` en el detalle"
              + " (BR-CAND-01); `IDEMPOTENCY_KEY_REUSED` si la clave se usó con otra vacante.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "429",
      description = "Se superó el límite de peticiones; reintentar tras `Retry-After`.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  public ResponseEntity<ApplicationResponse> submitApplication(
      Principal principal,
      @PathVariable String id,
      @Parameter(description = "Clave única por intento de envío; repetirla devuelve lo mismo.")
          @RequestHeader(name = "Idempotency-Key", required = false)
          String idempotencyKey) {
    var submitted =
        submit.execute(
            new SubmitApplicationCommand(caller(principal), uuidOrNotFound(id), idempotencyKey));
    return ResponseEntity.status(HttpStatus.CREATED).body(ApplicationResponse.from(submitted));
  }

  /**
   * Retirar no borra nada: la postulación se conserva y cambia de estado, por eso es un `POST` a un
   * subrecurso y no un `DELETE` (desvío del LLD §7, tdd/ats-tracker-modules.md §8).
   */
  @PostMapping("/api/v1/ats/applications/{id}/withdrawal")
  @Operation(
      summary = "Retirar una postulación propia",
      description =
          "Pasa una postulación en proceso a `WITHDRAWN` (REQ-C-03). Después se puede volver a"
              + " postular a la misma vacante mientras acepte postulaciones.",
      security = @SecurityRequirement(name = "bearer"))
  @ApiResponse(responseCode = "200", description = "La postulación retirada, con su historial.")
  @ApiResponse(
      responseCode = "401",
      description = "Falta el token, o no vale.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "403",
      description = "Sin la capacidad de candidato (`FORBIDDEN_CAPABILITY`).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "404",
      description = "La postulación no existe o es de otro candidato: no se distingue.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "409",
      description =
          "`INVALID_TRANSITION` si ya terminó (contratada, descartada o retirada);"
              + " `STALE_STATE` si otro cambio llegó antes.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "429",
      description = "Se superó el límite de peticiones; reintentar tras `Retry-After`.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  public CandidateApplicationResponse withdrawApplication(
      Principal principal, @PathVariable String id) {
    return CandidateApplicationResponse.from(
        withdraw.execute(new WithdrawApplicationCommand(caller(principal), uuidOrNotFound(id))));
  }

  // `sub` ya fue validado como UUID por el decodificador de la cadena (`SubjectValidator`).
  private static AccountId caller(Principal principal) {
    return new AccountId(UUID.fromString(principal.getName()));
  }

  // Un identificador que no es un UUID no puede existir: se trata como un recurso inexistente.
  private static UUID uuidOrNotFound(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException _) {
      throw new NotFoundException();
    }
  }
}
