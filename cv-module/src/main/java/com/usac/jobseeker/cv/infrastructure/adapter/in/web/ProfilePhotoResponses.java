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
 * Las respuestas de error que comparten las tres operaciones de la foto de perfil. A diferencia de
 * las escrituras del perfil, la foto no lleva versión: no hay `409`.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApiResponse(
    responseCode = "401",
    description = "Falta el token, o no vale (firma, vigencia, audiencia o tipo).",
    content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
@ApiResponse(
    responseCode = "403",
    description = "La cuenta no puede actuar: en eliminación, anonimizada o borrada.",
    content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
@ApiResponse(
    responseCode = "429",
    description = "Se superó el límite de peticiones; reintentar tras `Retry-After`.",
    content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
public @interface ProfilePhotoResponses {}
