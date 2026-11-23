package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.GetMyProfessionalProfileQuery;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.GetMyProfessionalProfileUseCase;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.SaveProfessionalProfileCommand;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.SaveProfessionalProfileResult;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.SaveProfessionalProfileUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Perfil profesional de la propia cuenta. Delgado y sin lógica: traduce HTTP al caso de uso y su
 * resultado al contrato. La identidad sale del token validado, nunca de un parámetro o del cuerpo.
 *
 * <p>Cualquier cuenta activa puede tener este perfil, sea cual sea su capacidad (BR-ACCT-02), así
 * que no se exige una capacidad concreta. El límite de tasa, el sobre de error y las métricas HTTP
 * son transversales y no se repiten aquí.
 */
@RestController
@Tag(name = "cv", description = "Perfil profesional y de candidato (módulo cv).")
public class ProfessionalProfileController {

  private static final String PATH = "/api/v1/me/professional-profile";

  /** El sobre estándar de error, declarado una vez en `app` (`OpenApiConfiguration`). */
  private static final String ERROR_ENVELOPE = "#/components/schemas/ErrorEnvelope";

  private final GetMyProfessionalProfileUseCase getProfile;
  private final SaveProfessionalProfileUseCase saveProfile;

  public ProfessionalProfileController(
      GetMyProfessionalProfileUseCase getProfile, SaveProfessionalProfileUseCase saveProfile) {
    this.getProfile = getProfile;
    this.saveProfile = saveProfile;
  }

  @GetMapping(PATH)
  @Operation(
      summary = "Consultar mi perfil profesional",
      description =
          "Devuelve el perfil profesional de quien hace la petición. Si todavía no lo creó, responde"
              + " 200 con `created: false` y `version: 0`: no es un error (BR-ACCT-09).",
      security = @SecurityRequirement(name = "bearer"))
  @ApiResponse(responseCode = "200", description = "El perfil, o el estado «sin crear».")
  @ApiResponse(
      responseCode = "401",
      description = "Falta el token, o no vale (firma, vigencia, audiencia o tipo).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "403",
      description = "La cuenta no puede actuar: en eliminación, anonimizada o borrada.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "429",
      description = "Se superó el límite de peticiones; reintentar tras `Retry-After`.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  public ProfessionalProfileResponse myProfile(Principal principal) {
    return ProfessionalProfileResponse.from(
        getProfile.execute(new GetMyProfessionalProfileQuery(caller(principal))));
  }

  @PutMapping(PATH)
  @Operation(
      summary = "Crear o actualizar mi perfil profesional",
      description =
          "Guarda nombre, titular y biografía. Al crear se envía `version: 0` y responde 201; al"
              + " editar se envía la versión leída y responde 200. Si los datos son iguales a los"
              + " guardados no cambia nada: la versión no sube y no se notifica a otros módulos.",
      security = @SecurityRequirement(name = "bearer"))
  @ApiResponse(responseCode = "200", description = "Perfil actualizado.")
  @ApiResponse(responseCode = "201", description = "Perfil creado.")
  @ApiResponse(
      responseCode = "400",
      description =
          "Algún dato no es válido (`VALIDATION_FAILED`, con el detalle por campo y sin el valor).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "401",
      description = "Falta el token, o no vale (firma, vigencia, audiencia o tipo).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "403",
      description = "La cuenta no puede actuar: en eliminación, anonimizada o borrada.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "409",
      description =
          "Otra edición se guardó antes (`STALE_STATE`): volver a leer el perfil y reintentar.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "429",
      description = "Se superó el límite de peticiones; reintentar tras `Retry-After`.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  public ResponseEntity<ProfessionalProfileResponse> saveMyProfile(
      Principal principal, @RequestBody SaveProfessionalProfileRequest request) {
    if (request.version() == null) {
      throw new ValidationFailedException(List.of(new FieldViolation("version", "REQUIRED")));
    }
    SaveProfessionalProfileResult result =
        saveProfile.execute(
            new SaveProfessionalProfileCommand(
                caller(principal),
                request.fullName(),
                request.headline(),
                request.bio(),
                request.version()));
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(ProfessionalProfileResponse.from(result.profile()));
  }

  // `sub` ya fue validado como UUID por el decodificador de la cadena (`SubjectValidator`).
  private static AccountId caller(Principal principal) {
    return new AccountId(UUID.fromString(principal.getName()));
  }
}
