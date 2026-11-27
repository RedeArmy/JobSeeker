package com.usac.jobseeker.app.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * TD-042, con el servidor real: las cabeceras `X-Forwarded-*` las aplica una válvula de Tomcat, que
 * MockMvc no ejecuta. Solo se cree a un proxy de confianza (aquí `127.0.0.1`); la misma petición
 * por `::1`, que no lo es, se trata como si las cabeceras no existieran.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InfrastructureTestConfiguration.class)
class TrustedProxyIntegrationTest {

  private static final String LOGIN = "/oauth2/authorization/clavaris";
  private static final HttpClient HTTP =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("server.tomcat.remoteip.internal-proxies", () -> "127\\.0\\.0\\.1");
    registry.add("jobseeker.ratelimit.enabled", () -> "true");
    registry.add("jobseeker.ratelimit.policies.login.capacity", () -> "1");
    registry.add("jobseeker.ratelimit.policies.login.refill-tokens", () -> "1");
  }

  @LocalServerPort private int port;

  private HttpResponse<String> get(String host, String path, String client, String proto)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + path));
    if (client != null) {
      request.header("X-Forwarded-For", client);
    }
    if (proto != null) {
      request.header("X-Forwarded-Proto", proto);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  // Detrás del proxy cada cliente tiene su propio cubo, no el de la IP del proxy.
  @Test
  void behindTheTrustedProxyEachClientHasItsOwnLimit() throws Exception {
    assertThat(get("127.0.0.1", LOGIN, "198.51.100.1", null).statusCode()).isNotEqualTo(429);
    assertThat(get("127.0.0.1", LOGIN, "198.51.100.2", null).statusCode())
        .as("otro cliente, otro cubo")
        .isNotEqualTo(429);
    assertThat(get("127.0.0.1", LOGIN, "198.51.100.1", null).statusCode())
        .as("el mismo cliente agota el suyo")
        .isEqualTo(429);
  }

  // Quien no es el proxy no puede evadir el límite cambiando la cabecera en cada petición.
  @Test
  void aClientThatIsNotTheProxyCannotForgeItsAddress() throws Exception {
    assertThat(get("[::1]", LOGIN, "198.51.100.10", null).statusCode()).isNotEqualTo(429);
    assertThat(get("[::1]", LOGIN, "198.51.100.11", null).statusCode())
        .as("la cabecera falsificada se ignora: es el mismo cubo")
        .isEqualTo(429);
  }

  // El esquema que vio el cliente (HTTPS en el proxy) es el que usa la aplicación: aquí lo delata
  // la cookie de sesión, que es `Secure` solo si la petición lo es.
  @Test
  void theSchemeComesFromTheTrustedProxyOnly() throws Exception {
    String trusted = sessionCookie(get("127.0.0.1", "/app/home", "198.51.100.20", "https"));
    String forged = sessionCookie(get("[::1]", "/app/home", "198.51.100.21", "https"));

    assertThat(trusted).containsIgnoringCase("secure");
    assertThat(forged).doesNotContainIgnoringCase("secure");
  }

  // El valor por defecto de TRUSTED_PROXIES es vacío. Tomcat lo toma como «ningún proxy», no como
  // su lista por defecto, que confía en todas las IP privadas: si una versión lo cambiara,
  // cualquier máquina de la red interna podría escribir su IP.
  @Test
  void anEmptyTrustedProxyListTrustsNobody() {
    var valve = new org.apache.catalina.valves.RemoteIpValve();
    assertThat(valve.getInternalProxies())
        .as("Tomcat confía por defecto en la red privada")
        .isNotNull();

    valve.setInternalProxies("");

    assertThat(valve.getInternalProxies()).isNull();
  }

  private static String sessionCookie(HttpResponse<String> response) {
    return String.join(";", response.headers().allValues("set-cookie"));
  }
}
