package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Las respuestas de error que comparte toda escritura del perfil de candidato, declaradas una vez.
 * Todas usan el sobre estándar de error, que `app` declara en un solo lugar.
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
    description =
        "La cuenta no puede actuar o no tiene la capacidad de candidato (`FORBIDDEN_CAPABILITY`).",
    content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
@ApiResponse(
    responseCode = "404",
    description = "Aún no hay perfil de candidato que reemplazar (se crea primero con `PUT`).",
    content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
@ApiResponse(
    responseCode = "409",
    description =
        "Otra edición se guardó antes (`STALE_STATE`): volver a leer el perfil y reintentar.",
    content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
@ApiResponse(
    responseCode = "429",
    description = "Se superó el límite de peticiones; reintentar tras `Retry-After`.",
    content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
public @interface CandidateWriteResponses {}
