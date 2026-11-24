package com.usac.jobseeker.matching.application.usecase.embedentity;

/**
 * Calcula el embedding del perfil de una cuenta a partir de **su estado actual** (nivel disparado,
 * ADR-0016): relee en vez de fiarse de qué evento la pidió, así un evento viejo reprocesado no
 * revierte nada. Llama a Ollama solo si el *hash* de la representación canónica cambió
 * (BR-MATCH-03).
 *
 * <p>No es transaccional: la llamada al modelo puede tardar segundos y no debe retener una
 * conexión. Cada escritura es una sentencia propia y condicional.
 */
public interface EmbedProfileUseCase {

  Result execute(EmbedProfileCommand command);

  enum Result {
    /** Se llamó al modelo y se guardó el vector nuevo. */
    EMBEDDED,
    /** El contenido no cambió: no se llamó al modelo; solo se sincronizó la descubribilidad. */
    UNCHANGED,
    /** La cuenta ya no tiene perfil legible: deja de ser descubrible. */
    HIDDEN,
    /** No hay nada que hacer: nunca tuvo perfil, o está anonimizado. */
    NOTHING_TO_DO
  }
}
