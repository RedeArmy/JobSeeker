package com.usac.jobseeker.matching.application.usecase.recomputescoresforentity;

/**
 * Puerto de entrada: tras procesar un perfil o una vacante, recalcula los puntajes **ya guardados**
 * de sus pares con motivo (BR-MATCH-03). No crea pares: solo mantiene al día los que alguien vio o
 * que postularon. Se ejecuta aunque el vector no haya cambiado, porque un cambio de datos
 * estructurados (salario, país) también cambia el puntaje.
 */
public interface RecomputeScoresForEntityUseCase {

  /** Cuántos pares se recalcularon. */
  int execute(RecomputeScoresCommand command);
}
