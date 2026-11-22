package com.usac.jobseeker.app.infrastructure.security;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.web.client.RestClient;

/**
 * Registro del cliente de Clavaris que se resuelve de forma perezosa
 * (docs/03-architecture/tdd/auth-module.md §4.4).
 *
 * <p>`ClientRegistrations.fromIssuerLocation` consulta a Clavaris al arrancar y hace fallar el
 * contexto si no responde; reiniciar JobSeeker durante una caída de Clavaris no debe impedirlo,
 * porque las sesiones viven en Redis. Aquí el descubrimiento se hace en la primera petición que lo
 * necesita, con tiempos de espera (ADR-0020, regla 12), se guarda al tener éxito y, si falla, no se
 * vuelve a intentar durante un rato para no multiplicar la espera por cada petición.
 */
public final class LazyClavarisClientRegistrationRepository
    implements ClientRegistrationRepository {

  public static final String REGISTRATION_ID = "clavaris";
  private static final String END_SESSION_ENDPOINT = "end_session_endpoint";
  private static final String REVOCATION_ENDPOINT = "revocation_endpoint";
  private static final Logger LOG =
      LoggerFactory.getLogger(LazyClavarisClientRegistrationRepository.class);

  private final ClavarisProperties properties;
  private final RestClient http;
  private final Clock clock;
  // AtomicReference y no `volatile`: la publicación segura de un objeto mutable no la da
  // `volatile`.
  private final AtomicReference<ClientRegistration> registration = new AtomicReference<>();
  private final AtomicReference<Instant> retryNotBefore = new AtomicReference<>(Instant.MIN);

  LazyClavarisClientRegistrationRepository(ClavarisProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
    factory.setReadTimeout(properties.readTimeout());
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public ClientRegistration findByRegistrationId(String registrationId) {
    if (!REGISTRATION_ID.equals(registrationId)) {
      return null;
    }
    ClientRegistration resolved = registration.get();
    if (resolved != null) {
      return resolved;
    }
    return discover();
  }

  // PMD.AvoidCatchingGenericException: la causa depende del cliente HTTP y de la red (tiempo
  // agotado,
  // 5xx, JSON mal formado); todas significan «reintentar más tarde», nunca «impedir el arranque».
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private synchronized ClientRegistration discover() {
    if (registration.get() != null) {
      return registration.get();
    }
    if (!properties.configured()) {
      LOG.warn("event=clavaris_not_configured reason=faltan_issuer_o_credenciales");
      return null;
    }
    if (clock.instant().isBefore(retryNotBefore.get())) {
      return null;
    }
    try {
      ClientRegistration discovered = fetch();
      registration.set(discovered);
      LOG.info("event=clavaris_discovered issuer={}", properties.issuerUri());
      return discovered;
    } catch (RuntimeException failure) {
      retryNotBefore.set(clock.instant().plus(properties.retryAfterFailure()));
      LOG.warn(
          "event=clavaris_discovery_failed reason={} retryAfter={}",
          failure.getClass().getSimpleName(),
          properties.retryAfterFailure());
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private ClientRegistration fetch() {
    String issuer = withoutTrailingSlashes(properties.issuerUri());
    Map<String, Object> metadata =
        http.get().uri(issuer + "/.well-known/openid-configuration").retrieve().body(Map.class);
    if (metadata == null) {
      throw new IllegalStateException("respuesta de descubrimiento vacía");
    }
    // El `issuer` del documento debe ser el configurado: si no, otro emisor se hace pasar por
    // Clavaris.
    if (!issuer.equals(withoutTrailingSlashes(String.valueOf(metadata.get("issuer"))))) {
      throw new IllegalStateException(
          "el issuer del descubrimiento no coincide con el configurado");
    }
    ClientRegistration.Builder builder =
        ClientRegistration.withRegistrationId(REGISTRATION_ID)
            .clientName("Clavaris")
            .clientId(properties.clientId())
            .clientSecret(properties.clientSecret())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope(properties.scopes())
            .authorizationUri(required(metadata, "authorization_endpoint"))
            .tokenUri(required(metadata, "token_endpoint"))
            .jwkSetUri(required(metadata, "jwks_uri"))
            .issuerUri(issuer)
            .userNameAttributeName(IdTokenClaimNames.SUB);
    if (metadata.get("userinfo_endpoint") != null) {
      builder.userInfoUri(metadata.get("userinfo_endpoint").toString());
    }
    // El cierre de sesión necesita el endpoint de fin de sesión (RP-initiated logout) y el de
    // revocación del refresh token (ADR-0020, regla 3); ambos son opcionales en el descubrimiento.
    Map<String, Object> extra = new HashMap<>();
    for (String key : new String[] {END_SESSION_ENDPOINT, REVOCATION_ENDPOINT}) {
      if (metadata.get(key) != null) {
        extra.put(key, metadata.get(key).toString());
      }
    }
    if (!extra.isEmpty()) {
      builder.providerConfigurationMetadata(extra);
    }
    return builder.build();
  }

  // Un bucle y no la expresión regular `/+$`: con ella el tiempo crece más que lineal ante una
  // cadena con muchas barras (S5852), y el valor viene de configuración y de la red.
  private static String withoutTrailingSlashes(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(0, end);
  }

  private static String required(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    if (value == null) {
      throw new IllegalStateException("falta " + key + " en el descubrimiento");
    }
    return value.toString();
  }
}
