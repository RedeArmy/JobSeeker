package com.usac.jobseeker.app.infrastructure.security;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Un Clavaris falso que solo sirve el documento de descubrimiento OIDC: basta para probar cómo
 * JobSeeker lo descubre y qué petición de autorización construye, sin levantar Clavaris.
 */
final class StubIssuer implements AutoCloseable {

  private final HttpServer server;
  private final String issuer;
  private final String advertisedIssuer;

  /** Cada petición recibida en un endpoint: `Authorization|cuerpo`. */
  final List<String> tokenRequests = new CopyOnWriteArrayList<>();

  final List<String> revokeRequests = new CopyOnWriteArrayList<>();
  volatile int tokenStatus = 200;
  volatile String tokenBody = "{\"access_token\":\"a\",\"refresh_token\":\"rt-nuevo\"}";
  volatile long tokenDelayMillis;
  volatile boolean advertiseRevocation = true;
  volatile int revokeStatus = 200;

  /** El conjunto de claves públicas que sirve `/oauth2/jwks`, y lo que le pasa al pedirlo. */
  volatile String jwks = "{\"keys\":[]}";

  volatile int jwksStatus = 200;
  volatile long jwksDelayMillis;
  final AtomicInteger jwksRequests = new AtomicInteger();

  /** Cuántas veces se pidió el documento de descubrimiento. */
  final AtomicInteger discoveryRequests = new AtomicInteger();

  /** Texto que el documento añade al final del emisor que anuncia (p. ej. barras). */
  volatile String advertisedIssuerSuffix = "";

  /** Claves que el documento de descubrimiento no debe traer, para probar las obligatorias. */
  final Set<String> omittedFromDiscovery = new CopyOnWriteArraySet<>();

  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  // Retrasa la respuesta sin dormir: espera un cierre que solo llega con `close()`, así un retraso
  // largo
  // no deja colgado el hilo del servidor al terminar la prueba.
  private final CountDownLatch closed = new CountDownLatch(1);

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
          discoveryRequests.incrementAndGet();
          Map<String, String> document = new LinkedHashMap<>();
          document.put("issuer", this.advertisedIssuer + advertisedIssuerSuffix);
          document.put("authorization_endpoint", issuer + "/oauth2/authorize");
          document.put("token_endpoint", issuer + "/oauth2/token");
          document.put("jwks_uri", issuer + "/oauth2/jwks");
          document.put("userinfo_endpoint", issuer + "/userinfo");
          document.put("end_session_endpoint", issuer + "/connect/logout");
          if (advertiseRevocation) {
            document.put("revocation_endpoint", issuer + "/oauth2/revoke");
          }
          omittedFromDiscovery.forEach(document::remove);
          byte[] body = JSON.writeValueAsString(document).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/o/test/oauth2/jwks",
        exchange -> {
          jwksRequests.incrementAndGet();
          if (jwksDelayMillis > 0) {
            try {
              closed.await(jwksDelayMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            }
          }
          byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(jwksStatus, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/o/test/oauth2/token",
        exchange -> {
          tokenRequests.add(capture(exchange));
          if (tokenDelayMillis > 0) {
            try {
              closed.await(tokenDelayMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            }
          }
          byte[] body = tokenBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(tokenStatus, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/o/test/oauth2/revoke",
        exchange -> {
          revokeRequests.add(capture(exchange));
          exchange.sendResponseHeaders(revokeStatus, -1);
          exchange.close();
        });
    server.start();
  }

  private static String capture(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    return authorization
        + "|"
        + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
  }

  String issuerUri() {
    return issuer;
  }

  @Override
  public void close() {
    closed.countDown();
    server.stop(0);
  }
}
