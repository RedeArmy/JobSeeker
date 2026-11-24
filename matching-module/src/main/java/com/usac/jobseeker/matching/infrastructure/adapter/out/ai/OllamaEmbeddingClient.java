package com.usac.jobseeker.matching.infrastructure.adapter.out.ai;

import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingClientPort;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingRejectedException;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingUnavailableException;
import com.usac.jobseeker.matching.domain.model.EmbeddableText;
import com.usac.jobseeker.matching.domain.model.EmbeddingVector;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente de embeddings de Ollama (`POST /api/embed`, ADR-0018). Habla HTTP directo y no a través
 * de una abstracción, porque lo que el ADR exige es explícito y no debe depender de un valor por
 * defecto ajeno: **`truncate=false` siempre** (un texto que no cabe es un error, no un vector de
 * una versión recortada en silencio), `num_ctx` fijo y el modelo residente (`keep_alive`).
 *
 * <p>Distingue los dos tipos de fallo, porque la cola reacciona distinto: lo que reintentar puede
 * arreglar (conexión, tiempo agotado, 5xx, 429) es {@link EmbeddingUnavailableException}; lo que no
 * (4xx, un vector de otra dimensión que la de la columna) es {@link EmbeddingRejectedException}.
 *
 * <p>Nunca registra ni incluye en un mensaje el texto que se embebe: aunque ya está redactado, es
 * el contenido de un perfil (BR-DATA-01).
 */
public class OllamaEmbeddingClient implements EmbeddingClientPort {

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final int TOO_MANY_REQUESTS = 429;
  private static final int SERVER_ERROR = 500;
  private static final int CLIENT_ERROR = 400;

  private final HttpClient http;
  private final URI endpoint;
  private final String modelName;
  private final int dimensions;
  private final int numCtx;
  private final String keepAlive;
  private final Duration timeout;

  public OllamaEmbeddingClient(
      HttpClient http,
      URI baseUri,
      String model,
      int dimensions,
      int numCtx,
      String keepAlive,
      Duration timeout) {
    this.http = http;
    this.endpoint = baseUri.resolve("/api/embed");
    this.modelName = model;
    this.dimensions = dimensions;
    this.numCtx = numCtx;
    this.keepAlive = keepAlive;
    this.timeout = timeout;
  }

  @Override
  public String model() {
    return modelName;
  }

  @Override
  public Embedded embed(EmbeddableText text) {
    HttpResponse<String> response = send(request(text));
    int status = response.statusCode();
    if (status == TOO_MANY_REQUESTS || status >= SERVER_ERROR) {
      throw new EmbeddingUnavailableException("Ollama respondió " + status, null);
    }
    if (status >= CLIENT_ERROR) {
      // El cuerpo de un 4xx de Ollama puede citar el texto: no se propaga.
      throw new EmbeddingRejectedException("Ollama rechazó la petición con " + status);
    }
    return new Embedded(parse(response.body()), modelName, false);
  }

  private HttpRequest request(EmbeddableText text) {
    var body = JSON.createObjectNode();
    body.put("model", modelName);
    body.put("input", text.value());
    // ADR-0018: nunca recortar en silencio. Si no cabe, Ollama responde un error y la cola lo
    // marca.
    body.put("truncate", false);
    body.put("keep_alive", keepAlive);
    body.putObject("options").put("num_ctx", numCtx);
    return HttpRequest.newBuilder(endpoint)
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
        .build();
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException failure) {
      throw new EmbeddingUnavailableException("Ollama no respondió", failure);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new EmbeddingUnavailableException("Se interrumpió la llamada a Ollama", interrupted);
    }
  }

  private EmbeddingVector parse(String body) {
    JsonNode first = JSON.readTree(body).path("embeddings").path(0);
    if (!first.isArray() || first.isEmpty()) {
      throw new EmbeddingRejectedException("Ollama no devolvió ningún vector");
    }
    if (first.size() != dimensions) {
      // Otro modelo que el configurado: el vector no cabe en la columna `vector(N)`.
      throw new EmbeddingRejectedException(
          "El vector tiene " + first.size() + " dimensiones y se esperaban " + dimensions);
    }
    float[] values = new float[first.size()];
    for (int i = 0; i < values.length; i++) {
      values[i] = first.get(i).floatValue();
    }
    return EmbeddingVector.of(values);
  }
}
