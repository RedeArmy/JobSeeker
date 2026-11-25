package com.usac.jobseeker.matching.application.usecase.embedentity;

/** Puerto de entrada: reacciona a un evento de `job` sin llamar al modelo (solo encola). */
public interface HandleJobEventUseCase {

  void execute(HandleJobEventCommand command);
}
