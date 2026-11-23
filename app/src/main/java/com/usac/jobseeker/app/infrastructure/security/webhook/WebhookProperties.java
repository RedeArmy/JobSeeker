package com.usac.jobseeker.app.infrastructure.security.webhook;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Receptor de webhooks de Clavaris (ADR-0020, superficie C).
 *
 * @param secret secreto de firma vigente; vacío deja el receptor cerrado (responde 503): sin él no
 *     hay forma de autenticar nada
 * @param previousSecret secreto anterior, válido mientras dure la rotación: Clavaris firma con
 *     ambos y se acepta cualquier firma `v1` que case con uno
 * @param tolerance diferencia máxima entre `t` y el reloj propio; acota la repetición de una
 *     entrega interceptada
 * @param maxBodyBytes tope del cuerpo, comprobado antes de leerlo entero
 */
@ConfigurationProperties("jobseeker.auth.webhook")
public record WebhookProperties(
    @DefaultValue("") String secret,
    @DefaultValue("") String previousSecret,
    @DefaultValue("5m") Duration tolerance,
    @DefaultValue("262144") int maxBodyBytes) {

  private static final Duration MAX_TOLERANCE = Duration.ofMinutes(15);

  public WebhookProperties {
    secret = secret == null ? "" : secret.strip();
    previousSecret = previousSecret == null ? "" : previousSecret.strip();
    if (tolerance.isNegative() || tolerance.isZero() || tolerance.compareTo(MAX_TOLERANCE) > 0) {
      throw new IllegalArgumentException(
          "jobseeker.auth.webhook.tolerance debe ser mayor que 0 y de 15 minutos como máximo");
    }
    if (maxBodyBytes <= 0) {
      throw new IllegalArgumentException("jobseeker.auth.webhook.max-body-bytes debe ser positivo");
    }
  }

  /** Los secretos con los que se verifica: el vigente y, si lo hay y es distinto, el anterior. */
  public List<String> secrets() {
    return Stream.of(secret, previousSecret).filter(s -> !s.isEmpty()).distinct().toList();
  }

  public boolean configured() {
    return !secrets().isEmpty();
  }
}
