package com.usac.jobseeker.matching.application.usecase.embedentity;

/**
 * Una pasada del trabajador de la cola de embeddings: reclama los trabajos listos y calcula cada
 * uno. No es transaccional: cada cálculo llama al modelo y no debe retener una conexión.
 */
public interface ProcessEmbeddingQueueUseCase {

  /**
   * @return cuántos trabajos se reclamaron en esta pasada
   */
  int execute();
}
