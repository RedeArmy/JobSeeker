package com.usac.jobseeker.cv.application.usecase.checkapplicationreadiness;

/**
 * Lo que BR-CAND-01 exige para postular y la cuenta aún no tiene. `ats` lo devuelve tal cual en el
 * error `APPLICATION_REQUIREMENTS_MISSING`, para que la pantalla lleve al candidato a completarlo.
 */
public enum MissingRequirement {
  /** Sin perfil profesional, que es donde vive el nombre (obligatorio en él). */
  FULL_NAME,
  /** Sin perfil de candidato, que es donde vive el correo de contacto (obligatorio en él). */
  CONTACT,
  /** Sin una versión vigente del CV. */
  CV
}
