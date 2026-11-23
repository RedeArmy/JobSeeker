package com.usac.jobseeker.app.infrastructure.ratelimit;

/**
 * Resultado de consumir una ficha.
 *
 * @param allowed si la petición puede seguir
 * @param limit capacidad del cubo (para `X-RateLimit-Limit`)
 * @param remaining fichas que quedan
 * @param retryAfterSeconds segundos hasta poder reintentar; 0 si se permitió
 */
public record RateLimitDecision(
    boolean allowed, long limit, long remaining, long retryAfterSeconds) {

  /** Cuando el almacén no responde se deja pasar (ver {@link RateLimiter}). */
  static RateLimitDecision unavailable(long limit) {
    return new RateLimitDecision(true, limit, limit, 0);
  }
}
