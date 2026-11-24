package com.usac.jobseeker.common.application.account;

import com.usac.jobseeker.common.domain.model.AccountId;

/**
 * Quien necesita enterarse de que una cuenta cambió en la base de datos. Hoy lo usa la memoria por
 * petición de la resolución de actor, que debe olvidar lo que sabía de esa cuenta: sin este aviso,
 * un caso de uso que cambie sus capacidades o su estado dentro de una petición dejaría a los
 * siguientes viendo la cuenta de antes.
 *
 * <p>Lo invoca **el adaptador que escribe** `accounts`, justo después de escribir, y no cada caso
 * de uso: así quien añada un caso de uso nuevo no puede olvidarlo. Se avisa aunque la transacción
 * luego se revierta: olvidar de más solo cuesta una consulta, y olvidar de menos sería un error de
 * acceso.
 */
@FunctionalInterface
public interface AccountChangeObserver {

  /** Nadie escucha: lo que usa un adaptador cuando no hay memoria que invalidar. */
  AccountChangeObserver NONE = accountId -> {};

  void accountChanged(AccountId accountId);
}
