package com.usac.jobseeker.tracker.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.tracker.application.usecase.getmyapplication.GetMyApplicationQuery;
import com.usac.jobseeker.tracker.application.usecase.getmyapplication.GetMyApplicationUseCase;
import com.usac.jobseeker.tracker.application.usecase.listmyapplications.ListMyApplicationsQuery;
import com.usac.jobseeker.tracker.application.usecase.listmyapplications.ListMyApplicationsUseCase;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * El tracker del candidato (tdd/ats-tracker-modules.md §4.2 y §8). Solo lectura: retirar una
 * postulación es de `ats`. El candidato sale del token, nunca de la ruta.
 */
@RestController
@Tag(name = "tracker", description = "Seguimiento de postulaciones del candidato (módulo tracker).")
public class TrackerController {

  private static final String ERROR_ENVELOPE = "#/components/schemas/ErrorEnvelope";
  private static final String PATH = "/api/v1/tracker/applications";

  private final ListMyApplicationsUseCase list;
  private final GetMyApplicationUseCase get;

  public TrackerController(ListMyApplicationsUseCase list, GetMyApplicationUseCase get) {
    this.list = list;
    this.get = get;
  }

  @GetMapping(PATH)
  @Operation(
      summary = "Mis postulaciones",
      description =
          "De la más reciente a la más antigua, con la vacante (también si se cerró o se eliminó),"
              + " el estado, la columna pública y el historial. Nunca el motivo de un descarte.",
      security = @SecurityRequirement(name = "bearer"))
  @ApiResponse(responseCode = "200", description = "Las postulaciones del candidato.")
  @ApiResponse(
      responseCode = "401",
      description = "Falta el token, o no vale.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "403",
      description = "Sin la capacidad de candidato (`FORBIDDEN_CAPABILITY`).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "429",
      description = "Se superó el límite de peticiones; reintentar tras `Retry-After`.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  public List<MyApplicationResponse> listMyApplications(Principal principal) {
    return list.execute(new ListMyApplicationsQuery(caller(principal))).stream()
        .map(MyApplicationResponse::from)
        .toList();
  }

  @GetMapping(PATH + "/{id}")
  @Operation(
      summary = "Una postulación propia",
      description = "El detalle con su historial completo (BR-TRACK-02).",
      security = @SecurityRequirement(name = "bearer"))
  @ApiResponse(responseCode = "200", description = "La postulación.")
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
      description = "No existe o es de otro candidato: no se distingue.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "429",
      description = "Se superó el límite de peticiones; reintentar tras `Retry-After`.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  public MyApplicationResponse getMyApplication(Principal principal, @PathVariable String id) {
    return MyApplicationResponse.from(
        get.execute(new GetMyApplicationQuery(caller(principal), uuidOrNotFound(id))));
  }

  // `sub` ya fue validado como UUID por el decodificador de la cadena (`SubjectValidator`).
  private static AccountId caller(Principal principal) {
    return new AccountId(UUID.fromString(principal.getName()));
  }

  // Un identificador que no es un UUID no puede existir.
  private static UUID uuidOrNotFound(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException _) {
      throw new NotFoundException();
    }
  }
}
