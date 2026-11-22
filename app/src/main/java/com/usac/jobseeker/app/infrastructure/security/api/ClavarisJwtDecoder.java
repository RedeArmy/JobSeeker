package com.usac.jobseeker.app.infrastructure.security.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import com.usac.jobseeker.app.infrastructure.security.ClavarisProperties;
import com.usac.jobseeker.app.infrastructure.security.LazyClavarisClientRegistrationRepository;
import java.net.http.HttpClient;
import java.text.ParseException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * Decodificador de los Bearer de Clavaris (ADR-0020, superficie B). Valida la firma RS256 contra el
 * JWKS de la organización (con recarga al ver un `kid` desconocido, que es como Clavaris rota), el
 * emisor, `exp`/`nbf`, y que sea un token de acceso emitido a esta aplicación y para una cuenta con
 * `sub` canónico.
 *
 * <p>Se construye de forma perezosa con el mismo registro que el login: arrancar JobSeeker con
 * Clavaris caído no debe impedirlo, y mientras no responda se lanza {@link
 * IdentityProviderUnavailableException}, que la cadena traduce a 503.
 *
 * <p>El algoritmo se fija a RS256: un token firmado con HS256 usando la clave pública como secreto
 * (el ataque clásico de confusión de algoritmo) se rechaza antes de mirar la firma.
 */
public final class ClavarisJwtDecoder implements JwtDecoder {

  private final ClientRegistrationRepository clients;
  private final ApiAuthProperties api;
  private final ClavarisProperties clavaris;
  private final Clock clock;
  private final AtomicReference<NimbusJwtDecoder> delegate = new AtomicReference<>();

  public ClavarisJwtDecoder(
      ClientRegistrationRepository clients,
      ApiAuthProperties api,
      ClavarisProperties clavaris,
      Clock clock) {
    this.clients = clients;
    this.api = api;
    this.clavaris = clavaris;
    this.clock = clock;
  }

  @Override
  public Jwt decode(String token) throws JwtException {
    rejectWhatCannotBeAnRs256Jwt(token);
    return resolvedDecoder().decode(token);
  }

  // Lo mal formado, sin firma o con otro algoritmo se rechaza antes de consultar a Clavaris: no
  // necesita su JWKS para saber que no vale, y así un token basura recibe 401 aunque Clavaris esté
  // caído en vez de un 503 que invitaría a sondear la API.
  private static void rejectWhatCannotBeAnRs256Jwt(String token) {
    JWT parsed;
    try {
      parsed = JWTParser.parse(token);
    } catch (ParseException malformed) {
      throw new BadJwtException("el token no es un JWT", malformed);
    }
    if (!(parsed instanceof SignedJWT signed)
        || !JWSAlgorithm.RS256.equals(signed.getHeader().getAlgorithm())) {
      throw new BadJwtException("el token no está firmado con RS256");
    }
  }

  private NimbusJwtDecoder resolvedDecoder() {
    NimbusJwtDecoder current = delegate.get();
    return current != null ? current : build();
  }

  private synchronized NimbusJwtDecoder build() {
    NimbusJwtDecoder current = delegate.get();
    if (current != null) {
      return current;
    }
    ClientRegistration registration =
        clients.findByRegistrationId(LazyClavarisClientRegistrationRepository.REGISTRATION_ID);
    if (registration == null) {
      throw new IdentityProviderUnavailableException(
          "el proveedor de identidad no está disponible");
    }
    NimbusJwtDecoder built = decoderFor(registration);
    delegate.set(built);
    return built;
  }

  private NimbusJwtDecoder decoderFor(ClientRegistration registration) {
    // Tiempos de espera obligatorios (ADR-0020, regla 12): sin ellos un JWKS colgado agotaría los
    // hilos.
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(clavaris.connectTimeout()).build());
    factory.setReadTimeout(clavaris.readTimeout());
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(new RestTemplate(factory))
            .build();
    JwtTimestampValidator timestamps = new JwtTimestampValidator(api.clockSkew());
    timestamps.setClock(clock);
    List<OAuth2TokenValidator<Jwt>> validators =
        List.of(
            timestamps,
            new JwtIssuerValidator(registration.getProviderDetails().getIssuerUri()),
            new AudienceValidator(api.effectiveAudiences(clavaris)),
            new AccessTokenValidator(),
            new SubjectValidator());
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
    return decoder;
  }
}
