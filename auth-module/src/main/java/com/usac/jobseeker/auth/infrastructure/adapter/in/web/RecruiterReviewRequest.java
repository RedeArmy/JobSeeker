package com.usac.jobseeker.auth.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import io.swagger.v3.oas.annotations.media.Schema;

/** El cuerpo de la decisión de un operador sobre un reclutador. */
@Schema(description = "La decisión sobre la verificación de un reclutador (BR-ACCT-11).")
public record RecruiterReviewRequest(
    @Schema(
            description =
                "`VERIFIED` sube el tope de vacantes publicadas, `SUSPENDED` lo retira por completo"
                    + " y `UNVERIFIED` devuelve a la cuenta a su estado inicial. Obligatorio.")
        RecruiterVerification status) {}
