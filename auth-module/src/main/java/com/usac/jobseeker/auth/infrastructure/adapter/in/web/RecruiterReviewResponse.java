package com.usac.jobseeker.auth.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import io.swagger.v3.oas.annotations.media.Schema;

/** El resultado de la decisión: el estado en que quedó la cuenta y si esta llamada lo cambió. */
@Schema(description = "El resultado de la decisión sobre un reclutador.")
public record RecruiterReviewResponse(
    @Schema(description = "El estado en que quedó la verificación.") RecruiterVerification status,
    @Schema(description = "`false` si la cuenta ya tenía ese estado: no se cambió ni se auditó.")
        boolean changed) {}
