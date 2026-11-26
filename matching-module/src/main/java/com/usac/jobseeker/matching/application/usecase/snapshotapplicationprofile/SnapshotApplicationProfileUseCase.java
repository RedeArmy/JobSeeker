package com.usac.jobseeker.matching.application.usecase.snapshotapplicationprofile;

/**
 * Puerto de entrada: al postular o al cambiar de etapa, guarda lo que el reclutador evalúa: el
 * puntaje de ese momento y, al postular, la copia inmutable del perfil canónico (ADR-0017, decisión
 * 9). La llama `ats`; hasta que exista, nadie la invoca. Es idempotente por postulación: repetirla
 * no duplica la copia del perfil.
 */
public interface SnapshotApplicationProfileUseCase {

  /** Qué quedó guardado; el perfil se guarda aunque el puntaje esté pendiente de un vector. */
  SnapshotResult execute(SnapshotApplicationProfileCommand command);

  /**
   * @param profileSnapshotted se guardó la copia del perfil canónico
   * @param scoreSnapshotted se guardó la foto del puntaje (falta si el vector aún no existe)
   */
  record SnapshotResult(boolean profileSnapshotted, boolean scoreSnapshotted) {}
}
