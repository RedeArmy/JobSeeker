package com.usac.jobseeker.job.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de `job` (`jobseeker.job.*`): nada de esto va fijo en código, porque se calibra con
 * el uso real.
 *
 * @param publishing los topes de vacantes publicadas a la vez según la confianza (REQ-R-10)
 */
@ConfigurationProperties("jobseeker.job")
public record JobProperties(@DefaultValue Publishing publishing) {

  /**
   * @param unverifiedMaxPublished vacantes que puede tener publicadas una cuenta sin verificar: lo
   *     bastante para probar el producto, y poco para un señuelo (amenaza T-N1)
   * @param verifiedMaxPublished el tope de una cuenta verificada; nunca menor que el anterior
   */
  public record Publishing(
      @DefaultValue("2") int unverifiedMaxPublished,
      @DefaultValue("100") int verifiedMaxPublished) {}
}
