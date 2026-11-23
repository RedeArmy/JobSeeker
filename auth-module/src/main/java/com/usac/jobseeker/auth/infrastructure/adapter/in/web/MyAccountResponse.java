package com.usac.jobseeker.auth.infrastructure.adapter.in.web;

import com.usac.jobseeker.auth.application.usecase.getmyaccount.MyAccountView;
import com.usac.jobseeker.common.domain.model.Capability;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Respuesta de `GET /api/v1/me`: el contrato público, separado del modelo interno. */
@Schema(description = "La cuenta de quien hace la petición, tal como la conoce JobSeeker.")
public record MyAccountResponse(
    @Schema(
            description =
                "Identificador de la cuenta: el `sub` que Clavaris asigna a la persona. Es el mismo en"
                    + " todas las aplicaciones de Clavaris.",
            example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
        String id,
    @Schema(
            description =
                "Capacidades habilitadas en la cuenta. Son independientes y no excluyentes: una misma"
                    + " cuenta puede ser candidato, reclutador o ambas (BR-ACCT-01).",
            example = "[\"CANDIDATE\"]")
        List<Capability> capabilities) {

  static MyAccountResponse from(MyAccountView view) {
    // Orden estable: un `Set` no lo garantiza y un contrato no debe cambiar de una respuesta a
    // otra.
    return new MyAccountResponse(
        view.id().value().toString(), view.capabilities().stream().sorted().toList());
  }
}
