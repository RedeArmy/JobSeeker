package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.changeavailability.ChangeAvailabilityCommand;
import com.usac.jobseeker.cv.application.usecase.changeavailability.ChangeAvailabilityUseCase;
import com.usac.jobseeker.cv.application.usecase.changediscoverability.ChangeDiscoverabilityCommand;
import com.usac.jobseeker.cv.application.usecase.changediscoverability.ChangeDiscoverabilityUseCase;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.AddAvailabilityExclusionCommand;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.ListAvailabilityExclusionsQuery;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.ManageAvailabilityExclusionsUseCase;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.RemoveAvailabilityExclusionCommand;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quién puede verme y cuándo: descubribilidad, disponibilidad y lista de exclusión del propio
 * candidato. Delgado y sin lógica: la identidad sale del token, nunca de la ruta ni del cuerpo, y
 * toda ruta exige la capacidad de candidato (BR-ACCT-04).
 *
 * <p>Descubribilidad y disponibilidad devuelven el **perfil completo** con su versión nueva, como
 * el resto de escrituras del perfil; la lista de exclusión no lleva versión y devuelve la lista.
 */
@RestController
@Tag(name = "cv", description = "Perfil profesional y de candidato (módulo cv).")
public class CandidateVisibilityController {

  private static final String PATH = "/api/v1/me/candidate-profile";
  private static final String EXCLUSIONS = PATH + "/availability-exclusions";
  private static final String BEARER = ApiDocs.BEARER;

  private final ChangeDiscoverabilityUseCase discoverability;
  private final ChangeAvailabilityUseCase availability;
  private final ManageAvailabilityExclusionsUseCase exclusions;

  public CandidateVisibilityController(
      ChangeDiscoverabilityUseCase discoverability,
      ChangeAvailabilityUseCase availability,
      ManageAvailabilityExclusionsUseCase exclusions) {
    this.discoverability = discoverability;
    this.availability = availability;
    this.exclusions = exclusions;
  }

  @PutMapping(PATH + "/discoverability")
  @Operation(
      summary = "Activar o desactivar mi descubribilidad",
      description =
          "Si es `false`, el perfil deja de aparecer en las sugerencias a reclutadores de inmediato"
              + " (BR-DATA-03); quien ya postuló sigue viéndolo (BR-VIS-02). Devuelve el perfil"
              + " completo con su versión nueva.",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "Perfil completo con su versión nueva.")
  @CandidateWriteResponses
  public CandidateProfileResponse changeDiscoverability(
      Principal principal, @RequestBody VisibilityRequests.Discoverability body) {
    if (body.discoverable() == null) {
      throw new ValidationFailedException(List.of(new FieldViolation("discoverable", "REQUIRED")));
    }
    return CandidateProfileResponse.from(
        discoverability.execute(
            new ChangeDiscoverabilityCommand(
                caller(principal), body.discoverable(), version(body.version()))));
  }

  @PutMapping(PATH + "/availability")
  @Operation(
      summary = "Declarar mi disponibilidad",
      description =
          "Cambia el orden de los sugeridos, nunca el score (BR-MATCH-07). Las cuentas de la lista de"
              + " exclusión lo ven como `UNSET` (BR-VIS-06). Devuelve el perfil completo con su"
              + " versión nueva.",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "Perfil completo con su versión nueva.")
  @CandidateWriteResponses
  public CandidateProfileResponse changeAvailability(
      Principal principal, @RequestBody VisibilityRequests.Availability body) {
    return CandidateProfileResponse.from(
        availability.execute(
            new ChangeAvailabilityCommand(
                caller(principal), body.status(), version(body.version()))));
  }

  @GetMapping(EXCLUSIONS)
  @Operation(
      summary = "Consultar mi lista de exclusión de disponibilidad",
      description = "Las cuentas, de cualquier rol, a las que se oculta mi estado `OPEN_TO_WORK`.",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(
      responseCode = ApiDocs.OK,
      description = "La lista, de la más reciente a la más antigua.")
  @CandidateWriteResponses
  public AvailabilityExclusionsResponse listExclusions(Principal principal) {
    return AvailabilityExclusionsResponse.from(
        exclusions.list(new ListAvailabilityExclusionsQuery(caller(principal))));
  }

  @PostMapping(EXCLUSIONS)
  @Operation(
      summary = "Excluir a una cuenta de mi disponibilidad",
      description =
          "La cuenta deja de ver mi estado `OPEN_TO_WORK` y lo ve como `UNSET`; sigue viendo el resto"
              + " de mi perfil y yo puedo seguir postulando (BR-CAND-06). Hasta 500 cuentas. `409"
              + " ALREADY_EXISTS` si ya estaba y `404` si la cuenta no existe.",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.CREATED, description = "Añadida; devuelve la lista completa.")
  @CandidateWriteResponses
  public ResponseEntity<AvailabilityExclusionsResponse> addExclusion(
      Principal principal, @RequestBody VisibilityRequests.Exclusion body) {
    var list =
        exclusions.add(
            new AddAvailabilityExclusionCommand(caller(principal), accountOf(body.accountId())));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(AvailabilityExclusionsResponse.from(list));
  }

  @DeleteMapping(EXCLUSIONS + "/{accountId}")
  @Operation(
      summary = "Quitar a una cuenta de mi lista de exclusión",
      description = "`404` si esa cuenta no estaba en la lista. Devuelve la lista completa.",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "Quitada; devuelve la lista completa.")
  @CandidateWriteResponses
  public AvailabilityExclusionsResponse removeExclusion(
      Principal principal, @PathVariable String accountId) {
    return AvailabilityExclusionsResponse.from(
        exclusions.remove(
            new RemoveAvailabilityExclusionCommand(caller(principal), accountOf(accountId))));
  }

  // Un identificador que no es un UUID es un dato inválido, no un recurso inexistente.
  private static AccountId accountOf(String raw) {
    try {
      return new AccountId(UUID.fromString(raw == null ? "" : raw.strip()));
    } catch (IllegalArgumentException _) {
      throw new ValidationFailedException(List.of(new FieldViolation("accountId", "INVALID")));
    }
  }

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
