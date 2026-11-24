package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** Los cuerpos de las operaciones de visibilidad del perfil de candidato. */
interface VisibilityRequests {

  @Schema(
      description = "Activar o desactivar que el perfil aparezca en las sugerencias (BR-DATA-03).")
  record Discoverability(
      @Schema(
              description =
                  "Obligatorio: `true` para ser descubrible, `false` para dejar de serlo.")
          Boolean discoverable,
      @Schema(
              description = "La versión del perfil de candidato que se leyó. Obligatoria.",
              example = "3")
          Long version) {}

  @Schema(description = "Declarar el estado de disponibilidad (BR-CAND-04).")
  record Availability(
      @Schema(description = "`OPEN_TO_WORK`, `NOT_LOOKING` o `UNSET` para retirar la declaración.")
          AvailabilityStatus status,
      @Schema(
              description = "La versión del perfil de candidato que se leyó. Obligatoria.",
              example = "3")
          Long version) {}

  @Schema(description = "Excluir a una cuenta: no verá tu estado `OPEN_TO_WORK` (BR-CAND-05).")
  record Exclusion(
      @Schema(description = "Identificador de la cuenta a excluir, de cualquier rol.")
          String accountId) {}
}
