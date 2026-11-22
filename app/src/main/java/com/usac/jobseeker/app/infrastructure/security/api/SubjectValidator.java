package com.usac.jobseeker.app.infrastructure.security.api;

import com.usac.jobseeker.common.domain.model.AccountId;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * El `sub` de un token de Clavaris es siempre un UUID canónico, y es el identificador de la cuenta
 * local (data-model.md §4.1). Un token cuyo `sub` no lo sea no puede venir de Clavaris: se rechaza
 * aquí, con 401, y no más adentro como un error de identidad.
 */
public final class SubjectValidator implements OAuth2TokenValidator<Jwt> {

  private static final OAuth2Error INVALID_SUBJECT =
      new OAuth2Error("invalid_token", "The token subject is not valid", null);

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    String subject = token.getSubject();
    if (subject == null) {
      return OAuth2TokenValidatorResult.failure(INVALID_SUBJECT);
    }
    try {
      AccountId.fromSubject(subject);
      return OAuth2TokenValidatorResult.success();
    } catch (IllegalArgumentException _) {
      return OAuth2TokenValidatorResult.failure(INVALID_SUBJECT);
    }
  }
}
