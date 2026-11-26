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
 * @param scoring pesos, `α`, calibración y completitud mínima del puntaje (BR-MATCH-05, ADR-0017)
 */
@ConfigurationProperties("jobseeker.matching")
public record MatchingProperties(
    @DefaultValue Embedding embedding,
    @DefaultValue Queue queue,
    @DefaultValue Suggestions suggestions,
    @DefaultValue Retrieval retrieval,
    @DefaultValue Scoring scoring) {

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
   * @param retrievalCap cuántos candidatos recupera la etapa 1 para puntuarlos en la etapa 2
   * @param tieTolerance puntos de ventaja con que `OPEN_TO_WORK` adelanta en el orden (BR-MATCH-07)
   */
  public record Suggestions(
      @DefaultValue("200") int viewQuota,
      @DefaultValue("24h") Duration window,
      @DefaultValue("50") int maxPageSize,
      @DefaultValue("200") int retrievalCap,
      @DefaultValue("1.0") double tieTolerance) {}

  /**
   * @param efSearch candidatos que explora el índice HNSW por consulta: más recall y más latencia;
   *     el valor por defecto sale de la medición de TD-012
   */
  public record Retrieval(@DefaultValue("100") int efSearch) {}

  /**
   * Los parámetros del puntaje: nada se codifica (BR-MATCH-05). Los valores por defecto son los del
   * ADR-0017 y de `03-lld.md` §2.3, y se calibran con el protocolo de 300 pares; cambiar cualquiera
   * produce otra `algorithm_version`.
   *
   * @param weights peso de cada dimensión estructurada; solo importa su proporción
   * @param alpha peso de la afinidad semántica calibrada frente a la estructurada
   * @param minCompleteness completitud mínima para entrar a las listas de sugeridos
   * @param mandatoryFactor veces que pesa un requisito obligatorio frente a uno deseable
   * @param calibration parámetros de Platt; sin ellos el puntaje se marca no calibrado
   * @param recomputeMaxPairs tope de pares guardados que se recalculan por entidad y pasada
   * @param migrationBatchSize pares por lote al migrar de versión del algoritmo
   * @param snapshotRetention cuánto se conservan las fotos del puntaje (24 meses, ADR-0017)
   */
  public record Scoring(
      @DefaultValue Weights weights,
      @DefaultValue("0.30") double alpha,
      @DefaultValue("0.5") double minCompleteness,
      @DefaultValue("2.0") double mandatoryFactor,
      @DefaultValue Calibration calibration,
      @DefaultValue("200") int recomputeMaxPairs,
      @DefaultValue("100") int migrationBatchSize,
      @DefaultValue("730d") Duration snapshotRetention) {}

  /**
   * Pesos por dimensión (`03-lld.md` §2.3): habilidades 35, experiencia 25, formación 15, ubicación
   * 10, salario 10, idiomas 5.
   */
  public record Weights(
      @DefaultValue("0.35") double skills,
      @DefaultValue("0.25") double experience,
      @DefaultValue("0.15") double education,
      @DefaultValue("0.10") double locationModality,
      @DefaultValue("0.10") double salary,
      @DefaultValue("0.05") double languages) {}

  /**
   * Parámetros de la sigmoide de Platt. Ausentes (ambos nulos) significa **sin calibrar**: el
   * puntaje se publica marcado como no calibrado hasta que el protocolo de 300 pares los produzca
   * (ADR-0017, decisión 5).
   */
  public record Calibration(Double a, Double b) {

    public boolean fitted() {
      return a != null && b != null;
    }
  }
}
