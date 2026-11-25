package com.usac.jobseeker.matching.application.usecase.embedentity;

/** Puerto de entrada: calcula el embedding de una vacante, o lo omite si no hace falta. */
public interface EmbedJobUseCase {

  /** Lo que hizo el cálculo. */
  enum Result {
    /** Se llamó al modelo y se guardó el vector. */
    EMBEDDED,
    /** El contenido y el modelo no cambiaron: no se llamó al modelo (BR-MATCH-03). */
    UNCHANGED,
    /** No está publicada: se actualizaron las copias y no se llamó al modelo. */
    NOT_PUBLISHED,
    /** No hay nada que hacer: no existe, es un borrador o está eliminada. */
    NOTHING_TO_DO
  }

  Result execute(EmbedJobCommand command);
}
