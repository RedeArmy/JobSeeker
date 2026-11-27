package com.usac.jobseeker.app.infrastructure.security.liveness;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Prueba de vida de la sesión con el refresh token (ADR-0020, reglas 1–8 y 12). Todos los valores
 * son configurables: se calibran con la latencia real de Clavaris, no se fijan en el código.
 *
 * @param renewalInterval cada cuánto se renueva por sesión (≤ 15 min, ADR-0020)
 * @param clavarisUnavailableMargin cuánto sobrevive una sesión sin una renovación correcta
 * @param failOpenEnabled `false` fuerza el cierre inmediato ante cualquier fallo (incidente)
 * @param retryOnceWithinGrace un único reintento ante tiempo agotado; solo con la ventana de
 *     reutilización de Clavaris configurada (30 s)
 * @param negativeCache tras un fallo transitorio no se reintenta esa sesión durante este tiempo
 * @param lockTtl vida del cerrojo; debe superar con holgura la duración máxima de una renovación
 * @param lockWait cuánto espera una petición al cerrojo antes de seguir sin renovar. Corto a
 *     propósito (TD-046): quien no lo obtiene sigue igualmente con el estado que tenía, que está
 *     dentro del margen, así que esperar más solo ocupaba un hilo de Tomcat mientras otra petición
 *     de la misma sesión hablaba con Clavaris
 */
@ConfigurationProperties("jobseeker.auth.liveness")
public record LivenessProperties(
    @DefaultValue("10m") Duration renewalInterval,
    @DefaultValue("2h") Duration clavarisUnavailableMargin,
    @DefaultValue("true") boolean failOpenEnabled,
    @DefaultValue("false") boolean retryOnceWithinGrace,
    @DefaultValue("45s") Duration negativeCache,
    @DefaultValue("30s") Duration lockTtl,
    @DefaultValue("250ms") Duration lockWait,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("5s") Duration readTimeout) {

  private static final Duration MAX_RENEWAL_INTERVAL = Duration.ofMinutes(15);

  public LivenessProperties {
    if (renewalInterval.isZero()
        || renewalInterval.isNegative()
        || renewalInterval.compareTo(MAX_RENEWAL_INTERVAL) > 0) {
      throw new IllegalArgumentException(
          "jobseeker.auth.liveness.renewal-interval debe estar entre 0 y 15 minutos (ADR-0020)");
    }
    // Regla 7: si el cerrojo expira con su dueño aún renovando, otra instancia rota el token y el
    // dueño lo reutiliza (reuse_detected, sesión destruida). Con reintento caben dos intentos.
    if (lockTtl.compareTo(connectTimeout.plus(readTimeout).multipliedBy(2)) <= 0) {
      throw new IllegalArgumentException(
          "jobseeker.auth.liveness.lock-ttl debe superar el doble de connect-timeout + read-timeout");
    }
  }
}
