package com.usac.jobseeker.app.infrastructure.security.api;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Exige que el token se haya emitido para esta aplicación (ADR-0020, G6). El decodificador por
 * defecto no mira `aud`, y todos los clientes de una organización comparten clave de firma: sin
 * esta comprobación valdría el token de cualquier otra aplicación de la organización.
 *
 * <p>La comparación es exacta (sin mayúsculas ni prefijos). Sin audiencias configuradas rechaza
 * todo: un error de configuración no debe abrir la API.
 */
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

  // ASCII a propósito: la descripción acaba en la cabecera WWW-Authenticate.
  private static final OAuth2Error NOT_FOR_THIS_APPLICATION =
      new OAuth2Error("invalid_token", "The token was not issued for this application", null);

  private final Set<String> allowed;

  public AudienceValidator(Collection<String> allowedAudiences) {
    this.allowed = Set.copyOf(allowedAudiences);
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    List<String> audience = token.getAudience();
    if (audience != null && audience.stream().anyMatch(allowed::contains)) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(NOT_FOR_THIS_APPLICATION);
  }
}
