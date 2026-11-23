package com.usac.jobseeker.app.infrastructure.ratelimit;

import jakarta.servlet.Filter;

/**
 * Fábrica de los filtros de límite de tasa. No se exponen como beans de tipo `Filter` a propósito:
 * Spring Boot registraría cada uno también como filtro global del servlet, fuera de las cadenas de
 * seguridad, y correría dos veces.
 */
public final class RateLimitFilters {

  private final RateLimitProperties properties;
  private final RateLimiter limiter;

  RateLimitFilters(RateLimitProperties properties, RateLimiter limiter) {
    this.properties = properties;
    this.limiter = limiter;
  }

  /** Políticas por IP, antes de autenticar. */
  public Filter beforeAuthentication() {
    return new RateLimitFilter(RateLimitFilter.Stage.BEFORE_AUTH, properties, limiter);
  }

  /** Políticas por cuenta, una vez resuelto el actor. */
  public Filter afterAuthentication() {
    return new RateLimitFilter(RateLimitFilter.Stage.AFTER_AUTH, properties, limiter);
  }
}
