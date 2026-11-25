package com.usac.jobseeker.matching.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de `matching` (`jobseeker.matching.*`, BR-MATCH-05): nada de esto va fijo en
 * código.
 *
 * @param embedding el modelo de embeddings y cómo se le habla (ADR-0018)
 * @param queue la cola de cálculo y la ventana de agrupación de ediciones (ADR-0017, decisión 10)
 * @param suggestions los topes de los candidatos sugeridos (REQ-R-11)
 * @param retrieval cómo se recorre el índice vectorial (TD-012)
 */
@ConfigurationProperties("jobseeker.matching")
public record MatchingProperties(
    @DefaultValue Embedding embedding,
    @DefaultValue Queue queue,
    @DefaultValue Suggestions suggestions,
    @DefaultValue Retrieval retrieval) {

  /**
   * @param host y @param port: dónde está Ollama
   * @param model el modelo vigente; provisional hasta que lo fije el protocolo de 300 pares
   * @param dimensions la dimensión de la columna `vector(N)`: un modelo con otra no se acepta
   * @param numContext `num_ctx` explícito, para que no dependa del valor por defecto del servidor
   * @param keepAlive cuánto se mantiene el modelo en memoria entre llamadas
   * @param timeout cuánto se espera una respuesta antes de tratarla como no disponible
   * @param maxCharacters presupuesto de la representación canónica; la reducción es determinista
   */
  public record Embedding(
      @DefaultValue("localhost") String host,
      @DefaultValue("11434") int port,
      @DefaultValue("qwen3-embedding:0.6b") String model,
      @DefaultValue("1024") int dimensions,
      @DefaultValue("8192") int numContext,
      @DefaultValue("30m") String keepAlive,
      @DefaultValue("60s") Duration timeout,
      @DefaultValue("12000") int maxCharacters) {}

  /**
   * @param pollInterval cada cuánto mira el trabajador
   * @param window ventana de agrupación de ediciones: varias ediciones seguidas son un cálculo
   * @param batchSize trabajos por pasada
   * @param lease cuánto se reserva un trabajo; debe superar al `timeout` del modelo
   * @param maxAttempts intentos antes de dejarlo en FAILED
   * @param baseBackoff y @param maxBackoff espera entre intentos
   */
  public record Queue(
      @DefaultValue("2s") Duration pollInterval,
      @DefaultValue("30s") Duration window,
      @DefaultValue("10") int batchSize,
      @DefaultValue("5m") Duration lease,
      @DefaultValue("12") int maxAttempts,
      @DefaultValue("5s") Duration baseBackoff,
      @DefaultValue("5m") Duration maxBackoff) {}

  /**
   * @param viewQuota perfiles únicos que un reclutador puede ver por ventana: lo bastante para
   *     trabajar con comodidad y poco para recorrer la base de candidatos (amenaza T-N1)
   * @param window la ventana móvil de la cuota
   * @param maxPageSize el máximo de candidatos por petición
   */
  public record Suggestions(
      @DefaultValue("200") int viewQuota,
      @DefaultValue("24h") Duration window,
      @DefaultValue("50") int maxPageSize) {}

  /**
   * @param efSearch candidatos que explora el índice HNSW por consulta: más recall y más latencia;
   *     el valor por defecto sale de la medición de TD-012
   */
  public record Retrieval(@DefaultValue("100") int efSearch) {}
}
