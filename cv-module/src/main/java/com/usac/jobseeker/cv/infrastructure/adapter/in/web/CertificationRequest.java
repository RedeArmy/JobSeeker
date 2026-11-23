package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** Cuerpo para añadir o reemplazar una certificación. */
@Schema(description = "Una certificación del perfil profesional.")
public record CertificationRequest(
    @Schema(description = "Obligatorio, hasta 200 caracteres.", example = "AWS Solutions Architect")
        String name,
    @Schema(description = "Obligatoria, hasta 200 caracteres.", example = "Amazon Web Services")
        String issuingOrganization,
    @Schema(description = "Opcional.", example = "2024-01-15") LocalDate issueDate,
    @Schema(description = "Opcional; sin ella no expira.", example = "2027-01-15")
        LocalDate expirationDate,
    @Schema(
            description =
                "Opcional. Solo direcciones `http` o `https`; cualquier otro esquema se rechaza.",
            example = "https://example.com/credencial/123")
        String credentialUrl,
    @Schema(description = "La versión del perfil que el cliente leyó. Obligatoria.", example = "3")
        Long version) {}
