package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.cv.domain.model.SalaryPeriod;
import com.usac.jobseeker.cv.domain.model.WorkModality;
import com.usac.jobseeker.cv.domain.model.WorkSchedule;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * Cuerpo de `PUT /api/v1/me/candidate-profile`. No lleva identificador de cuenta (sale del token)
 * ni descubribilidad ni disponibilidad, que tienen sus propias operaciones.
 */
@Schema(
    description = "El contacto y los datos de elegibilidad que el candidato edita de su perfil.")
public record SaveCandidateProfileRequest(
    @Schema(
            description = "Obligatorio. Es el correo con el que postula.",
            example = "ana@example.com")
        String contactEmail,
    @Schema(description = "Opcional: dígitos y separadores, hasta 30.", example = "+502 5555-1234")
        String contactPhone,
    @Schema(description = "Opcional, ISO 3166-1 alfa-2.", example = "GT") String countryOfResidence,
    @Schema(description = "Opcional.") WorkModality desiredModality,
    @Schema(description = "Opcional.") WorkSchedule preferredWorkSchedule,
    @Schema(description = "Opcional; sin decirlo equivale a no.") Boolean relocationWillingness,
    @Schema(
            description =
                "Opcional, pero si se da algún dato de salario hacen falta moneda y periodo.",
            example = "3000")
        BigDecimal salaryMin,
    @Schema(example = "4500") BigDecimal salaryMax,
    @Schema(description = "ISO 4217.", example = "USD") String salaryCurrency,
    SalaryPeriod salaryPeriod,
    @Schema(
            description = "La versión que el cliente leyó; `0` al crear el perfil. Obligatoria.",
            example = "3")
        Long version) {}
