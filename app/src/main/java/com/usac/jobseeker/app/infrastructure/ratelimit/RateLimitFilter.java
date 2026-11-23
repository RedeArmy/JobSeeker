package com.usac.jobseeker.app.infrastructure.ratelimit;

import com.usac.jobseeker.app.infrastructure.ratelimit.RateLimitProperties.KeyKind;
import com.usac.jobseeker.app.infrastructure.ratelimit.RateLimitProperties.Policy;
import com.usac.jobseeker.app.infrastructure.security.CurrentActor;
import com.usac.jobseeker.app.infrastructure.security.ErrorEnvelope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Aplica el límite de tasa (ADR-0011) en dos puntos de una misma cadena, porque la clave depende de
 * si ya se sabe quién llama:
 *
 * <ul>
 *   <li>{@link Stage#BEFORE_AUTH}: políticas por IP (login, webhook). Va antes de cualquier
 *       procesamiento, así un atacante no gasta nada de la aplicación para ser rechazado.
 *   <li>{@link Stage#AFTER_AUTH}: políticas por cuenta. Va tras resolver el actor; una petición
 *       anónima que llegue aquí se limita por IP.
 * </ul>
 *
 * <p>Al exceder el límite responde `429` con el sobre estándar, `Retry-After` y `X-RateLimit-*`.
 */
public class RateLimitFilter extends OncePerRequestFilter {

  /** Punto de la cadena en el que corre este filtro. */
  public enum Stage {
    BEFORE_AUTH,
    AFTER_AUTH
  }

  private final Stage stage;
  private final RateLimitProperties properties;
  private final RateLimiter limiter;

  RateLimitFilter(Stage stage, RateLimitProperties properties, RateLimiter limiter) {
    this.stage = stage;
    this.properties = properties;
    this.limiter = limiter;
  }

  /** Dos instancias en una cadena: sin esto la segunda se saltaría como «ya filtrada». */
  @Override
  protected String getAlreadyFilteredAttributeName() {
    return RateLimitFilter.class.getName() + "." + stage;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String policyName = RateLimitRules.policyFor(request).orElse(null);
    if (!properties.enabled() || policyName == null || !appliesAtThisStage(policyName)) {
      chain.doFilter(request, response);
      return;
    }
    Policy policy = properties.policies().get(policyName);
    RateLimitDecision decision = limiter.tryConsume(policyName, policy, keyOf(request, policy));
    response.setHeader("X-RateLimit-Limit", Long.toString(decision.limit()));
    response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
    if (decision.allowed()) {
      chain.doFilter(request, response);
      return;
    }
    response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
    ErrorEnvelope.write(
        request,
        response,
        429,
        "RATE_LIMIT_EXCEEDED",
        "Demasiadas peticiones. Reintenta más tarde.");
  }

  private boolean appliesAtThisStage(String policyName) {
    boolean byIp = properties.policies().get(policyName).key() == KeyKind.IP;
    return byIp == (stage == Stage.BEFORE_AUTH);
  }

  /**
   * La cuenta autenticada o, sin ella, la IP. Se usa la dirección del socket y no
   * `X-Forwarded-For`: esa cabecera la escribe cualquiera salvo que haya un proxy de confianza
   * delante, y confiar en ella permitiría evadir el límite cambiándola en cada petición.
   */
  private static String keyOf(HttpServletRequest request, Policy policy) {
    if (policy.key() == KeyKind.ACCOUNT) {
      return CurrentActor.from(request)
          .map(actor -> actor.id().value().toString())
          .orElseGet(request::getRemoteAddr);
    }
    return request.getRemoteAddr();
  }
}
