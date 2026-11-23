package com.usac.jobseeker.app.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sobre de error estándar (api-contract-overview.md §6) para las respuestas que genera la propia
 * capa de seguridad: nunca lleva datos sensibles (BR-DATA-01) y siempre trae el `traceId`.
 */
public final class ErrorEnvelope {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ErrorEnvelope() {}

  public static void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(JSON.writeValueAsString(body(request, code, message)));
  }

  /** El cuerpo del sobre, para quien lo devuelve como objeto (p. ej. el manejador global). */
  public static Map<String, Object> body(HttpServletRequest request, String code, String message) {
    return body(request, code, message, List.of());
  }

  /**
   * Igual, con el detalle por campo de una validación fallida. Solo nombre de campo y código: el
   * valor recibido puede ser un dato personal y nunca vuelve en la respuesta (BR-DATA-01).
   */
  public static Map<String, Object> body(
      HttpServletRequest request, String code, String message, List<Map<String, String>> details) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("code", code);
    error.put("message", message);
    error.put("traceId", TraceIdFilter.traceId(request));
    error.put("timestamp", Instant.now().toString());
    if (!details.isEmpty()) {
      error.put("details", details);
    }
    return Map.of("error", error);
  }
}
