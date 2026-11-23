package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

/**
 * Textos de la documentación OpenAPI que se repiten en muchas operaciones. Las anotaciones solo
 * admiten constantes, y repetir el mismo literal doce veces es la forma más fácil de que dos
 * operaciones acaben describiéndose distinto.
 */
final class ApiDocs {

  /** El sobre estándar de error, declarado una vez en `app` (`OpenApiConfiguration`). */
  static final String ERROR_ENVELOPE = "#/components/schemas/ErrorEnvelope";

  static final String BEARER = "bearer";
  static final String OK = "200";
  static final String CREATED = "201";

  static final String ADD_DESCRIPTION =
      "Añade un elemento y devuelve el perfil completo con su versión nueva. Hasta 50 elementos por"
          + " colección.";
  static final String REPLACE_DESCRIPTION =
      "Reemplaza el elemento por completo y devuelve el perfil con su versión nueva.";
  static final String REMOVE_DESCRIPTION =
      "Quita el elemento. La versión del perfil que se leyó va en el parámetro `version`.";
  static final String ADDED = "Elemento añadido; perfil completo.";
  static final String REPLACED = "Elemento reemplazado; perfil completo.";
  static final String REMOVED = "Elemento quitado; perfil completo.";

  private ApiDocs() {}
}
