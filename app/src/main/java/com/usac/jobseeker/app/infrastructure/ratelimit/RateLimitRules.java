package com.usac.jobseeker.app.infrastructure.ratelimit;

import com.usac.jobseeker.app.infrastructure.security.webhook.ClavarisWebhookFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Qué política se aplica a una petición (ADR-0011). Es un único selector transversal y no una
 * anotación por endpoint: un endpoint nuevo queda limitado sin que nadie se acuerde de pedirlo, y
 * lo único que hay que decidir es si es de lectura o de escritura.
 */
final class RateLimitRules {

  static final String LOGIN = "login";
  static final String WEBHOOK = "webhook";
  static final String WRITE = "write";
  static final String READ = "read";
  static final String SUGGESTIONS = "suggestions";
  static final String UPLOAD = "upload";

  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

  private RateLimitRules() {}

  /**
   * La política de la petición, o vacío si no se limita: los endpoints operativos (sondeos de salud
   * y Prometheus, que no son tráfico de cliente) y los recursos estáticos.
   */
  static Optional<String> policyFor(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (isExempt(path)) {
      return Optional.empty();
    }
    if (path.startsWith("/oauth2/authorization/") || path.startsWith("/login/oauth2/code/")) {
      return Optional.of(LOGIN);
    }
    if (path.equals(ClavarisWebhookFilter.PATH)) {
      return Optional.of(WEBHOOK);
    }
    // Los sugeridos son la superficie más sensible al raspado de datos de candidatos (amenaza
    // T-N1):
    // tienen su propio cubo, más estricto que el de lectura, además de la cuota de perfiles únicos.
    if (path.startsWith("/api/v1/matching/jobs/") && path.endsWith("/suggestions")) {
      return Optional.of(SUGGESTIONS);
    }
    // Toda subida de archivo tiene su propio cubo (ADR-0021 §4): cuesta un escaneo antivirus y
    // almacenamiento. Se reconoce por el tipo de contenido, así una subida nueva queda limitada sin
    // añadir su ruta aquí.
    String contentType = request.getContentType();
    if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
      return Optional.of(UPLOAD);
    }
    return Optional.of(SAFE_METHODS.contains(request.getMethod()) ? READ : WRITE);
  }

  private static boolean isExempt(String path) {
    return path.startsWith("/actuator/")
        || path.startsWith("/css/")
        || path.startsWith("/js/")
        || path.equals("/favicon.ico")
        || path.equals("/error");
  }
}
