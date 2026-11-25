package com.usac.jobseeker.common.domain.model;

/**
 * Cuánta confianza se le tiene a una cuenta reclutadora (REQ-R-10). Sin verificar, publica pocas
 * vacantes y no recibe sugerencias de candidatos: un reclutador falso que publica una vacante
 * señuelo para raspar datos de candidatos es la amenaza T-N1 del modelo de amenazas.
 *
 * <p>Vive en `common` porque la escribe `auth` y la consultan `job` y, más adelante, `matching`: un
 * módulo no puede nombrar un tipo del dominio de otro (ARCH-04), y duplicar el enumerado en cada
 * consumidor solo añadiría una traducción sin decisión que tomar.
 */
public enum RecruiterVerification {
  /** El valor de toda cuenta nueva: puede trabajar, con topes bajos. */
  UNVERIFIED,
  /** Alguien comprobó que es quien dice ser; sube sus topes. */
  VERIFIED,
  /** Se le retiró la confianza: no publica ni reabre vacantes. */
  SUSPENDED
}
