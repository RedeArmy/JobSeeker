package com.usac.jobseeker.app.infrastructure.security.liveness;

import com.usac.jobseeker.app.infrastructure.security.LazyClavarisClientRegistrationRepository;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renueva y revoca el refresh token llamando al endpoint de tokens de Clavaris con tiempos de
 * espera obligatorios (ADR-0020, regla 12): sin ellos, un Clavaris colgado dejaría las demás
 * peticiones de la sesión detenidas detrás del cerrojo hasta agotar los hilos.
 *
 * <p>Se hace la llamada directamente y no con el cliente de refresh de Spring para controlar la
 * clasificación: solo `invalid_grant` cierra la sesión; `invalid_client` (secreto rotado), un 5xx o
 * un tiempo agotado se tratan como transitorios para no desconectar a todos por un fallo de
 * configuración o ajeno.
 */
public final class ClavarisRefreshTokenClient implements RefreshTokenClient {

  private static final Logger LOG = LoggerFactory.getLogger(ClavarisRefreshTokenClient.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final long RETRY_PAUSE_MILLIS = 1_000;
  private static final String REFRESH_TOKEN = "refresh_token";

  private final ClientRegistrationRepository clients;
  private final RestClient http;
  private final boolean retryOnceOnTimeout;

  public ClavarisRefreshTokenClient(
      ClientRegistrationRepository clients, LivenessProperties properties) {
    this.clients = clients;
    this.retryOnceOnTimeout = properties.retryOnceWithinGrace();
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
    factory.setReadTimeout(properties.readTimeout());
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public Result refresh(String refreshToken) {
    ClientRegistration registration =
        clients.findByRegistrationId(LazyClavarisClientRegistrationRepository.REGISTRATION_ID);
    if (registration == null) {
      return new Result.Transient("registro_no_disponible");
    }
    Result result = attemptRefresh(registration, refreshToken);
    // Regla 12: el reintento único va dentro del mismo intento y solo con la ventana de
    // reutilización
    // de Clavaris configurada; un token rotado cuya respuesta se perdió se recupera dentro de ella.
    if (retryOnceOnTimeout
        && result instanceof Result.Transient transientFailure
        && "red_o_tiempo_agotado".equals(transientFailure.reason())) {
      pause();
      result = attemptRefresh(registration, refreshToken);
    }
    return result;
  }

  @Override
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // best-effort: nunca debe romper el logout
  public boolean revoke(String refreshToken) {
    ClientRegistration registration =
        clients.findByRegistrationId(LazyClavarisClientRegistrationRepository.REGISTRATION_ID);
    if (registration == null) {
      return false;
    }
    String endpoint = revocationEndpoint(registration);
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("token", refreshToken);
    form.add("token_type_hint", REFRESH_TOKEN);
    try {
      http.post()
          .uri(endpoint)
          .headers(h -> h.setBasicAuth(registration.getClientId(), registration.getClientSecret()))
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (RuntimeException failure) {
      LOG.warn("event=refresh_token_revoke_failed reason={}", failure.getClass().getSimpleName());
      return false;
    }
  }

  private Result attemptRefresh(ClientRegistration registration, String refreshToken) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "refresh_token");
    form.add(REFRESH_TOKEN, refreshToken);
    try {
      String body =
          http.post()
              .uri(registration.getProviderDetails().getTokenUri())
              .headers(
                  h -> h.setBasicAuth(registration.getClientId(), registration.getClientSecret()))
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .accept(MediaType.APPLICATION_JSON)
              .body(form)
              .retrieve()
              .body(String.class);
      return new Result.Renewed(newRefreshToken(body));
    } catch (HttpStatusCodeException rejected) {
      if (isInvalidGrant(rejected.getResponseBodyAsString())) {
        return new Result.InvalidGrant();
      }
      return new Result.Transient("http_" + rejected.getStatusCode().value());
    } catch (ResourceAccessException network) {
      return new Result.Transient("red_o_tiempo_agotado");
    } catch (RuntimeException unexpected) { // NOPMD: cualquier otra causa es «reintentar luego»
      return new Result.Transient("inesperado_" + unexpected.getClass().getSimpleName());
    }
  }

  private static boolean isInvalidGrant(String body) {
    try {
      JsonNode error = JSON.readTree(body).path("error");
      return "invalid_grant".equals(error.asString());
    } catch (JacksonException notJson) {
      return false;
    }
  }

  private static String newRefreshToken(String body) {
    try {
      JsonNode token = JSON.readTree(body).path(REFRESH_TOKEN);
      return token.isString() ? token.asString() : null;
    } catch (JacksonException notJson) {
      // Una respuesta 200 ilegible no prueba nada sobre la cuenta: se conserva el token actual.
      return null;
    }
  }

  private static String revocationEndpoint(ClientRegistration registration) {
    Object advertised =
        registration.getProviderDetails().getConfigurationMetadata().get("revocation_endpoint");
    if (advertised != null) {
      return advertised.toString();
    }
    // Spring Authorization Server la sirve junto al endpoint de tokens.
    return registration.getProviderDetails().getTokenUri().replaceAll("/token$", "/revoke");
  }

  private static void pause() {
    try {
      Thread.sleep(RETRY_PAUSE_MILLIS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
