package com.usac.jobseeker.app.infrastructure.security.api;

import org.springframework.security.oauth2.jwt.JwtException;

/**
 * No se pudo comprobar el token porque Clavaris no responde. No es un `BadJwtException`: no prueba
 * que el token sea malo, así que la API responde 503 y no 401.
 */
public final class IdentityProviderUnavailableException extends JwtException {

  private static final long serialVersionUID = 1L;

  public IdentityProviderUnavailableException(String message) {
    super(message);
  }
}
