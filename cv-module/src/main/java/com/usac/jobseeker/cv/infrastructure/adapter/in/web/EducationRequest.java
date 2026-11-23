package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.cv.domain.model.EducationLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** Cuerpo para añadir o reemplazar una formación académica. */
@Schema(description = "Una formación académica del perfil profesional.")
public record EducationRequest(
    @Schema(
            description = "Obligatoria, hasta 200 caracteres.",
            example = "Universidad de San Carlos")
        String institution,
    @Schema(
            description = "Obligatorio, hasta 200 caracteres.",
            example = "Ingeniería en Ciencias y Sistemas")
        String degree,
    @Schema(
            description =
                "Nivel normalizado, opcional. Es el que usan las reglas de elegibilidad por nivel"
                    + " académico: sin él, la formación no cuenta para ellas.")
        EducationLevel level,
    @Schema(description = "Opcional; sin ella la formación está en curso.", example = "2025-12-15")
        LocalDate completionDate,
    @Schema(description = "La versión del perfil que el cliente leyó. Obligatoria.", example = "3")
        Long version) {}
