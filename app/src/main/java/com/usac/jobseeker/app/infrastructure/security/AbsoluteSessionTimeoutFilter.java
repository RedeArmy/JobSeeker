package com.usac.jobseeker.app.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Límite absoluto de la sesión (ADR-0020: ≤ 8 h): la inactividad ya la limita Redis, pero una
 * sesión que se renueva sin parar no debe vivir para siempre. Es también la barrera final si fallan
 * los webhooks y la prueba de vida (los webhooks aceleran el cierre, no son la única defensa).
 */
final class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

  private final Duration limit;
  private final Clock clock;

  AbsoluteSessionTimeoutFilter(Duration limit, Clock clock) {
    this.limit = limit;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    HttpSession session = request.getSession(false);
    if (session != null
        && Instant.ofEpochMilli(session.getCreationTime()).plus(limit).isBefore(clock.instant())) {
      session.invalidate();
    }
    chain.doFilter(request, response);
  }
}
