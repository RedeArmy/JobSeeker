package com.usac.jobseeker.job.infrastructure.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Las respuestas de error que comparten las operaciones sobre una vacante propia, declaradas una
 * vez. Todas usan el sobre estándar de error, que `app` declara en un solo lugar.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApiResponse(
    responseCode = "400",
    description = "Algún dato no es válido (`VALIDATION_FAILED`, con el detalle por campo).",
    content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
@ApiResponse(
    responseCode = "401",
    description = "Falta el token, o no vale (firma, vigencia, audiencia o tipo).",
    content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
@ApiResponse(
    responseCode = "403",
    description = "La cuenta no puede actuar o no tiene la capacidad de reclutador.",
    content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
@ApiResponse(
    responseCode = "404",
    description =
        "La vacante no existe, está eliminada o es de otro reclutador: no se distingue (BR-VIS-03).",
    content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
@ApiResponse(
    responseCode = "409",
    description =
        "`STALE_STATE` si otra edición se guardó antes; `INVALID_TRANSITION` si el cambio de estado"
            + " no existe; `INVALID_STATE` si la operación no aplica al estado actual.",
    content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
@ApiResponse(
    responseCode = "429",
    description = "Se superó el límite de peticiones; reintentar tras `Retry-After`.",
    content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
public @interface JobWriteResponses {}
