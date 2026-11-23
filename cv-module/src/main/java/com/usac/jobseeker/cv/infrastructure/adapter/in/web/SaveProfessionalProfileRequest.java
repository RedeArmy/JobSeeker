package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cuerpo de `PUT /api/v1/me/professional-profile`. No lleva identificador de cuenta: sale del
 * token, así nadie puede escribir el perfil de otro (BR-ACCT-04).
 */
@Schema(description = "Los datos que el titular edita de su perfil profesional.")
public record SaveProfessionalProfileRequest(
    @Schema(description = "Obligatorio, hasta 200 caracteres.", example = "Ana María López")
        String fullName,
    @Schema(
            description = "Opcional, hasta 200 caracteres, una sola línea.",
            example = "Backend Engineer")
        String headline,
    @Schema(description = "Opcional, hasta 4000 caracteres.") String bio,
    @Schema(
            description =
                "La versión que el cliente leyó; `0` al crear el perfil por primera vez. Obligatoria.",
            example = "3")
        Long version) {}
