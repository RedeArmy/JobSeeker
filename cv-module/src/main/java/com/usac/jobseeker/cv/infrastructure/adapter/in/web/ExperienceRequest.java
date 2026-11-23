package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** Cuerpo para añadir o reemplazar una experiencia. La cuenta sale del token, nunca del cuerpo. */
@Schema(description = "Una experiencia laboral del perfil profesional.")
public record ExperienceRequest(
    @Schema(description = "Obligatorio, hasta 200 caracteres.", example = "Acme S.A.")
        String company,
    @Schema(description = "Obligatorio, hasta 200 caracteres.", example = "Backend Engineer")
        String title,
    @Schema(description = "Obligatoria, entre 1900 y 2100.", example = "2022-03-01")
        LocalDate startDate,
    @Schema(description = "Opcional; sin ella es la experiencia actual.", example = "2024-06-30")
        LocalDate endDate,
    @Schema(description = "Opcional, hasta 4000 caracteres.") String description,
    @Schema(description = "La versión del perfil que el cliente leyó. Obligatoria.", example = "3")
        Long version) {}
