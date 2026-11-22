package com.usac.jobseeker.app.infrastructure.security.api;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Solo un token de acceso sirve como Bearer. El ID token de Clavaris se emite al mismo cliente, con
 * la misma firma y el mismo `iss`/`aud`, y vive 30 minutos en vez de 5: sin esta comprobación
 * valdría como credencial de la API. Los tokens de acceso llevan `scope`; los ID tokens no.
 */
public final class AccessTokenValidator implements OAuth2TokenValidator<Jwt> {

  private static final OAuth2Error NOT_AN_ACCESS_TOKEN =
      new OAuth2Error("invalid_token", "The token is not an access token", null);

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    if (token.hasClaim("scope") || token.hasClaim("scp")) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(NOT_AN_ACCESS_TOKEN);
  }
}
