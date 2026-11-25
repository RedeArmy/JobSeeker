package com.usac.jobseeker.app.infrastructure.ratelimit;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Políticas de límite de tasa (ADR-0011). Los valores viven en configuración y no en el código: se
 * calibran con tráfico real sin volver a desplegar.
 *
 * @param enabled apagarlo solo es para diagnóstico; en producción siempre encendido
 * @param policies por nombre: `login`, `webhook`, `write`, `read` y `suggestions` deben existir,
 *     porque el selector de rutas las asigna a toda petición
 */
@ConfigurationProperties("jobseeker.ratelimit")
public record RateLimitProperties(
    @DefaultValue("true") boolean enabled, @DefaultValue Map<String, Policy> policies) {

  /** Políticas que el selector de rutas asigna: sin una de ellas el arranque falla. */
  static final java.util.List<String> REQUIRED =
      java.util.List.of("login", "webhook", "write", "read", "suggestions");

  /** Qué identifica a quien se limita. */
  public enum KeyKind {
    /** La cuenta autenticada; sin cuenta (petición anónima) se cae a la IP. */
    ACCOUNT,
    /** La IP de origen: lo único que hay antes de autenticar (login, webhook). */
    IP
  }

  /**
   * @param capacity fichas del cubo: la ráfaga máxima que se tolera
   * @param refillTokens fichas que se reponen cada {@code refillPeriod}
   */
  public record Policy(int capacity, int refillTokens, Duration refillPeriod, KeyKind key) {

    public Policy {
      if (capacity < 1 || refillTokens < 1) {
        throw new IllegalArgumentException("capacity y refill-tokens deben ser positivos");
      }
      if (refillPeriod.compareTo(Duration.ZERO) <= 0) {
        throw new IllegalArgumentException("refill-period debe ser positivo");
      }
    }
  }

  public RateLimitProperties {
    policies = Map.copyOf(policies);
    // Falla al arrancar y no en la primera petición: una política ausente dejaría rutas sin límite.
    for (String required : REQUIRED) {
      if (!policies.containsKey(required)) {
        throw new IllegalArgumentException(
            "Falta la política jobseeker.ratelimit.policies." + required);
      }
    }
  }
}
