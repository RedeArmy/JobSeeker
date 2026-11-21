package com.usac.jobseeker.app.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Identificador de correlación de cada petición (ADR-0012, NFR-OBS-01): entra en el MDC (y por
 * tanto en cada línea de log estructurado), en la cabecera de respuesta y en el sobre de error. Se
 * genera siempre aquí: aceptar uno del cliente permitiría inyectar texto arbitrario en los logs.
 */
final class TraceIdFilter extends OncePerRequestFilter {

  static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".TRACE_ID";
  static final String HEADER = "X-Trace-Id";
  static final String MDC_KEY = "traceId";

  static String traceId(HttpServletRequest request) {
    Object value = request.getAttribute(ATTRIBUTE);
    return value == null ? "" : value.toString();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = UUID.randomUUID().toString();
    request.setAttribute(ATTRIBUTE, traceId);
    response.setHeader(HEADER, traceId);
    MDC.put(MDC_KEY, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
