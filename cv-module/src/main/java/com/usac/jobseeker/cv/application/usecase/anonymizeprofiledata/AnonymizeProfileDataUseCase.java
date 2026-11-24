package com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata;

/**
 * Anonimiza **solo** los datos de `cv` de una cuenta cuyo periodo de gracia venció (BR-DATA-05,
 * ADR-0013). Lo invoca el lote de cumplimiento, cuenta por cuenta; qué módulo es dueño de ese lote
 * lo decide el TDD de «Cumplimiento y ciclo de vida de cuenta», no este.
 *
 * <p>Es idempotente: ejecutarla otra vez sobre una cuenta ya anonimizada no hace nada ni emite
 * evento, de modo que el lote puede reintentar sin riesgo.
 */
public interface AnonymizeProfileDataUseCase {

  Result execute(AnonymizeProfileDataCommand command);

  /** Si esta llamada anonimizó algo, o ya no quedaba nada que anonimizar. */
  enum Result {
    ANONYMIZED,
    NOTHING_TO_ANONYMIZE
  }
}
