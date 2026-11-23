package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.cv.domain.model.ProjectType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** Cuerpo para añadir o reemplazar un proyecto. */
@Schema(description = "Un proyecto laboral o personal del perfil profesional.")
public record ProjectRequest(
    @Schema(description = "Obligatorio, hasta 200 caracteres.", example = "JobSeeker") String name,
    @Schema(description = "Opcional, hasta 4000 caracteres.") String description,
    @Schema(description = "Obligatorio: `WORK` (laboral) o `PERSONAL`.", example = "PERSONAL")
        ProjectType type,
    @Schema(description = "Opcional, hasta 200 caracteres.", example = "Autor") String role,
    @Schema(description = "Opcional.", example = "2026-01-10") LocalDate startDate,
    @Schema(description = "Opcional; sin ella el proyecto está en curso.") LocalDate endDate,
    @Schema(
            description = "Opcional. Solo direcciones `http` o `https`.",
            example = "https://github.com/ejemplo/proyecto")
        String url,
    @Schema(description = "La versión del perfil que el cliente leyó. Obligatoria.", example = "3")
        Long version) {}
