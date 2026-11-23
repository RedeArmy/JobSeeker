package com.usac.jobseeker.auth.infrastructure.adapter.in.web;

import com.usac.jobseeker.auth.application.usecase.getmyaccount.GetMyAccountQuery;
import com.usac.jobseeker.auth.application.usecase.getmyaccount.GetMyAccountUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Plantilla de un endpoint (paso 5 del Sprint 0): delgado y sin lógica. Traduce HTTP a la consulta
 * del caso de uso y su resultado al contrato público. La identidad sale del token validado, nunca
 * de un parámetro: nadie puede pedir la cuenta de otro (BR-ACCT-04).
 *
 * <p>Quien llega aquí ya pasó la cadena de la API: Bearer válido de Clavaris, audiencia correcta y
 * una cuenta local activa (`ActorFilter`). El límite de tasa, el sobre de error y las métricas HTTP
 * son transversales y no se repiten en el controlador.
 */
@RestController
@Tag(name = "auth", description = "Cuenta e identidad (módulo auth).")
public class MyAccountController {

  /** El sobre estándar de error, declarado una vez en `app` (`OpenApiConfiguration`). */
  private static final String ERROR_ENVELOPE = "#/components/schemas/ErrorEnvelope";

  private final GetMyAccountUseCase getMyAccount;

  public MyAccountController(GetMyAccountUseCase getMyAccount) {
    this.getMyAccount = getMyAccount;
  }

  @GetMapping("/api/v1/me")
  @Operation(
      summary = "Consultar mi cuenta",
      description =
          "Devuelve la cuenta de quien hace la petición: su identificador y las capacidades"
              + " (candidato, reclutador o ambas) que tiene habilitadas.",
      security = @SecurityRequirement(name = "bearer"))
  @ApiResponse(responseCode = "200", description = "La cuenta de quien llama.")
  @ApiResponse(
      responseCode = "401",
      description = "Falta el token, o no vale (firma, vigencia, audiencia o tipo).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "403",
      description = "La cuenta existe pero no puede actuar: en eliminación, anonimizada o borrada.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "404",
      description = "El token es válido pero la cuenta no existe en JobSeeker.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "429",
      description = "Se superó el límite de peticiones; reintentar tras `Retry-After`.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  public MyAccountResponse myAccount(Principal principal) {
    // `sub` ya fue validado como UUID por el decodificador de la cadena (`SubjectValidator`).
    AccountId caller = new AccountId(UUID.fromString(principal.getName()));
    return getMyAccount
        .execute(new GetMyAccountQuery(caller))
        .map(MyAccountResponse::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
