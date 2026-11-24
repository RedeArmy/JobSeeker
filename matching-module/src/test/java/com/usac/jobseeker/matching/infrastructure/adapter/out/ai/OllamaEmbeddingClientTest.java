package com.usac.jobseeker.matching.infrastructure.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingRejectedException;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingUnavailableException;
import com.usac.jobseeker.matching.domain.model.EmbeddableText;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * El cliente de Ollama contra un servidor HTTP de prueba: lo que ADR-0018 exige que se envíe
 * (`truncate=false`, `num_ctx`, `keep_alive`) y cómo se clasifica cada fallo, sin un Ollama real.
 */
class OllamaEmbeddingClientTest {

  private HttpServer server;
  private final AtomicReference<String> body = new AtomicReference<>();
  private int status = 200;
  private String answer = "{\"embeddings\":[[0.5,0.25,0.125]]}";
  private long delayMillis;

  private OllamaEmbeddingClient client(int dimensions, Duration timeout) throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/api/embed",
        exchange -> {
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          if (delayMillis > 0) {
            // parkNanos puede volver antes de tiempo: se espera hasta el plazo real.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
            while (System.nanoTime() < deadline) {
              LockSupport.parkNanos(deadline - System.nanoTime());
            }
          }
          byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    return new OllamaEmbeddingClient(
        HttpClient.newHttpClient(),
        URI.create("http://localhost:" + server.getAddress().getPort()),
        "qwen3-embedding:0.6b",
        dimensions,
        8192,
        "30m",
        timeout);
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void itSendsTheModelTheTextAndEverythingTheAdrDemandsExplicitly() throws Exception {
    var result = client(3, Duration.ofSeconds(5)).embed(EmbeddableText.of("Java y SQL"));

    var sent = JsonMapper.builder().build().readTree(body.get());
    assertThat(sent.path("model").asString()).isEqualTo("qwen3-embedding:0.6b");
    assertThat(sent.path("input").asString()).isEqualTo("Java y SQL");
    assertThat(sent.path("truncate").isBoolean()).isTrue();
    assertThat(sent.path("truncate").asBoolean(true)).as("truncate=false siempre").isFalse();
    assertThat(sent.path("options").path("num_ctx").asInt()).isEqualTo(8192);
    assertThat(sent.path("keep_alive").asString()).isEqualTo("30m");
    assertThat(result.vector().values()).containsExactly(0.5f, 0.25f, 0.125f);
    assertThat(result.model()).isEqualTo("qwen3-embedding:0.6b");
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void whatIsSentIsAlreadyRedacted() throws Exception {
    client(3, Duration.ofSeconds(5)).embed(EmbeddableText.of("escríbeme a ana@example.com"));

    assertThat(body.get()).doesNotContain("ana@example.com").contains("[correo]");
  }

  @Test
  void aServerErrorOrATooManyRequestsIsUnavailableBecauseRetryingMayWork() throws Exception {
    var client = client(3, Duration.ofSeconds(5));
    var text = EmbeddableText.of("Java");

    status = 503;
    assertThatThrownBy(() -> client.embed(text)).isInstanceOf(EmbeddingUnavailableException.class);
    status = 429;
    assertThatThrownBy(() -> client.embed(text)).isInstanceOf(EmbeddingUnavailableException.class);
  }

  @Test
  void aClientErrorIsRejectedAndNeverEchoesTheTextOrTheServerBody() throws Exception {
    status = 400;
    answer = "{\"error\":\"input length exceeds context: SECRETO-DEL-PERFIL\"}";
    var client = client(3, Duration.ofSeconds(5));

    var text = EmbeddableText.of("texto del perfil");

    assertThatThrownBy(() -> client.embed(text))
        .isInstanceOf(EmbeddingRejectedException.class)
        .hasMessageNotContaining("SECRETO")
        .hasMessageNotContaining("texto del perfil")
        .hasMessageContaining("400");
  }

  @Test
  void aVectorOfAnotherDimensionIsRejectedBecauseItWouldNotFitTheColumn() throws Exception {
    var client = client(1024, Duration.ofSeconds(5));
    var text = EmbeddableText.of("Java");

    assertThatThrownBy(() -> client.embed(text))
        .isInstanceOf(EmbeddingRejectedException.class)
        .hasMessageContaining("3 dimensiones")
        .hasMessageContaining("1024");
  }

  @Test
  void anAnswerWithoutAVectorIsRejected() throws Exception {
    answer = "{\"embeddings\":[]}";
    var client = client(3, Duration.ofSeconds(5));
    var text = EmbeddableText.of("Java");

    assertThatThrownBy(() -> client.embed(text)).isInstanceOf(EmbeddingRejectedException.class);
  }

  @Test
  void aServerThatIsDownIsUnavailable() {
    var client =
        new OllamaEmbeddingClient(
            HttpClient.newHttpClient(),
            URI.create("http://localhost:1"),
            "m",
            3,
            8192,
            "30m",
            Duration.ofSeconds(2));
    var text = EmbeddableText.of("Java");

    assertThatThrownBy(() -> client.embed(text)).isInstanceOf(EmbeddingUnavailableException.class);
  }

  @Test
  void aServerThatDoesNotAnswerInTimeIsUnavailable() throws Exception {
    delayMillis = 1500;
    var client = client(3, Duration.ofMillis(300));
    var text = EmbeddableText.of("Java");

    assertThatThrownBy(() -> client.embed(text)).isInstanceOf(EmbeddingUnavailableException.class);
  }

  @Test
  void theModelIsTheConfiguredOne() throws Exception {
    assertThat(client(3, Duration.ofSeconds(5)).model()).isEqualTo("qwen3-embedding:0.6b");
  }
}
