package com.usac.jobseeker.matching.application.usecase.embedentity;

/**
 * Lo que `matching` hace al recibir un evento de `cv`. Es rápido y no llama al modelo: encola el
 * cálculo, o borra el vector si la cuenta se anonimizó. Es idempotente, así que la entrega al menos
 * una vez de la bandeja no necesita una marca en `processed_events`.
 */
public interface HandleProfileEventUseCase {

  void execute(HandleProfileEventCommand command);
}
