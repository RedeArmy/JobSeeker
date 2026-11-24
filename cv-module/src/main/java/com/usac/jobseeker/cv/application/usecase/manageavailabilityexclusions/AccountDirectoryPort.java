package com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions;

import com.usac.jobseeker.common.domain.model.AccountId;

/**
 * Puerto de salida: ¿se puede excluir a esta cuenta? `cv` no lee `accounts`, se lo pregunta a
 * `auth`.
 */
public interface AccountDirectoryPort {

  /**
   * Si la cuenta existe y una persona puede volver a verla actuar: activa, o en el periodo de
   * gracia de un borrado que aún puede cancelar (BR-DATA-04). Una cuenta nunca aprovisionada,
   * anonimizada o sin identidad no vuelve: excluirla solo acumularía filas sin efecto.
   */
  boolean isLive(AccountId accountId);
}
