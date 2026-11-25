package com.usac.jobseeker.job.infrastructure.adapter.in.web;

/**
 * Textos de la documentación OpenAPI que se repiten en muchas operaciones. Las anotaciones solo
 * admiten constantes, y repetir el mismo literal en cada operación es la forma más fácil de que dos
 * acaben describiéndose distinto.
 */
final class ApiDocs {

  /** El sobre estándar de error, declarado una vez en `app` (`OpenApiConfiguration`). */
  static final String ERROR_ENVELOPE = "#/components/schemas/ErrorEnvelope";

  static final String BEARER = "bearer";
  static final String OK = "200";
  static final String CREATED = "201";
  static final String VERSION_DESCRIPTION =
      "La versión de la vacante que se leyó. Obligatoria: si otra edición se guardó antes, se"
          + " rechaza con `409 STALE_STATE`.";

  private ApiDocs() {}
}
