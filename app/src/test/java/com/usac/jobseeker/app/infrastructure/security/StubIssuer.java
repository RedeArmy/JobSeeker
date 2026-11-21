package com.usac.jobseeker.app.infrastructure.security;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Un Clavaris falso que solo sirve el documento de descubrimiento OIDC: basta para probar cómo
 * JobSeeker lo descubre y qué petición de autorización construye, sin levantar Clavaris.
 */
final class StubIssuer implements AutoCloseable {

  private final HttpServer server;
  private final String issuer;
  private final String advertisedIssuer;

  StubIssuer() throws IOException {
    this(null);
  }

  /** Con {@code advertisedIssuer} distinto, simula un emisor que se hace pasar por otro. */
  StubIssuer(String advertisedIssuer) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    this.issuer = base + "/o/test";
    this.advertisedIssuer = advertisedIssuer == null ? issuer : advertisedIssuer;
    server.createContext(
        "/o/test/.well-known/openid-configuration",
        exchange -> {
          byte[] body =
              ("{\"issuer\":\""
                      + this.advertisedIssuer
                      + "\",\"authorization_endpoint\":\""
                      + issuer
                      + "/oauth2/authorize\",\"token_endpoint\":\""
                      + issuer
                      + "/oauth2/token\",\"jwks_uri\":\""
                      + issuer
                      + "/oauth2/jwks\",\"userinfo_endpoint\":\""
                      + issuer
                      + "/userinfo\",\"end_session_endpoint\":\""
                      + issuer
                      + "/connect/logout\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  String issuerUri() {
    return issuer;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
