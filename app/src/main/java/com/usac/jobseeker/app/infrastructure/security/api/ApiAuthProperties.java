package com.usac.jobseeker.app.infrastructure.security.api;

import com.usac.jobseeker.app.infrastructure.security.ClavarisProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Autenticación Bearer de la API (ADR-0020, superficie B).
 *
 * @param allowedAudiences audiencias (`aud`) aceptadas; vacía significa «solo el `client_id` de
 *     JobSeeker». Se amplía al dar de alta un cliente programático o móvil.
 * @param allowedOrigins orígenes CORS permitidos; vacía (el valor del MVP) cierra CORS por completo
 * @param clockSkew tolerancia de reloj para `exp` y `nbf`; los tokens viven 300 s, así que una
 *     tolerancia grande alargaría de forma apreciable su vida real
 */
@ConfigurationProperties("jobseeker.auth.api")
public record ApiAuthProperties(
    @DefaultValue List<String> allowedAudiences,
    @DefaultValue List<String> allowedOrigins,
    @DefaultValue("30s") Duration clockSkew) {

  private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(2);

  public ApiAuthProperties {
    allowedAudiences = withoutBlanks(allowedAudiences);
    allowedOrigins = withoutBlanks(allowedOrigins);
    // ADR-0020: CORS cerrado por defecto y con una lista explícita; «*» lo abriría a cualquier
    // sitio.
    if (allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
      throw new IllegalArgumentException(
          "jobseeker.auth.api.allowed-origins no admite comodines: lista cada origen");
    }
    if (clockSkew.isNegative() || clockSkew.compareTo(MAX_CLOCK_SKEW) > 0) {
      throw new IllegalArgumentException(
          "jobseeker.auth.api.clock-skew debe estar entre 0 y 2 minutos");
    }
  }

  /**
   * Audiencias que se aceptan de verdad: las configuradas o, sin ellas, el `client_id`. Sin ninguna
   * de las dos la lista queda vacía y todo token se rechaza (falla cerrado).
   */
  public List<String> effectiveAudiences(ClavarisProperties clavaris) {
    if (!allowedAudiences.isEmpty()) {
      return allowedAudiences;
    }
    String clientId = clavaris.clientId();
    return clientId == null || clientId.isBlank() ? List.of() : List.of(clientId.trim());
  }

  private static List<String> withoutBlanks(List<String> values) {
    return values.stream().map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
  }
}
