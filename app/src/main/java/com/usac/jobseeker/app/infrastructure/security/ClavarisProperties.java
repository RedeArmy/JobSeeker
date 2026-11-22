package com.usac.jobseeker.app.infrastructure.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cliente OIDC de Clavaris (ADR-0020). Los valores salen del entorno (.env.example); ninguno tiene
 * un valor secreto por defecto. Los tiempos de espera son obligatorios: sin ellos el cliente HTTP
 * por defecto no termina frente a un Clavaris colgado y agota los hilos (ADR-0020, regla 12).
 */
@ConfigurationProperties("jobseeker.auth.clavaris")
public record ClavarisProperties(
    String issuerUri,
    String clientId,
    String clientSecret,
    // `offline_access` pide el refresh token con el que se comprueba que la cuenta sigue activa
    // (ADR-0020, prueba de vida).
    @DefaultValue({"openid", "profile", "email", "offline_access"}) List<String> scopes,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("10s") Duration readTimeout,
    @DefaultValue("10s") Duration retryAfterFailure) {

  public boolean configured() {
    return notBlank(issuerUri) && notBlank(clientId) && notBlank(clientSecret);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }
}
