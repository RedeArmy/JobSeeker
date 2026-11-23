package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Optional;

/** Respuesta del perfil profesional: el contrato público, separado del modelo interno. */
@Schema(description = "El perfil profesional de la cuenta, o el estado «aún sin crear».")
public record ProfessionalProfileResponse(
    @Schema(
            description =
                "`false` si la cuenta todavía no creó su perfil: los demás campos vienen vacíos y"
                    + " `version` es 0, que es lo que se envía al crearlo.")
        boolean created,
    @Schema(description = "Identificador del perfil; vacío si aún no existe.") String id,
    @Schema(description = "Nombre completo.", example = "Ana María López") String fullName,
    @Schema(description = "Titular profesional.", example = "Backend Engineer") String headline,
    @Schema(description = "Biografía; admite varias líneas.") String bio,
    @Schema(
            description =
                "Versión del perfil. Debe devolverse al guardar: si otra edición se guardó antes, la"
                    + " petición se rechaza con `409 STALE_STATE` en vez de pisarla.",
            example = "3")
        long version,
    @Schema(description = "Última edición, en UTC (ISO-8601).", example = "2026-11-23T16:30:00Z")
        String updatedAt) {

  /** El perfil aún no existe: lo único que el cliente necesita es saberlo y la versión inicial. */
  static ProfessionalProfileResponse notCreated() {
    return new ProfessionalProfileResponse(false, null, null, null, null, 0, null);
  }

  static ProfessionalProfileResponse from(ProfessionalProfileView view) {
    return new ProfessionalProfileResponse(
        true,
        view.id().toString(),
        view.fullName(),
        view.headline(),
        view.bio(),
        view.version(),
        view.updatedAt().toString());
  }

  static ProfessionalProfileResponse from(Optional<ProfessionalProfileView> view) {
    return view.map(ProfessionalProfileResponse::from)
        .orElseGet(ProfessionalProfileResponse::notCreated);
  }
}
