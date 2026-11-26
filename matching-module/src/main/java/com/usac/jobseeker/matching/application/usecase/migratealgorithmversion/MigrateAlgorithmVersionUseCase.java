package com.usac.jobseeker.matching.application.usecase.migratealgorithmversion;

/**
 * Puerto de entrada: pone al día, por lotes, los puntajes guardados con **otra** versión del
 * algoritmo (ADR-0017, decisión 10). Antes de reemplazar cada uno guarda una foto del anterior
 * (`VERSION_CHANGED`), para poder comparar versiones (BR-MATCH-04). Corre en segundo plano y nunca
 * bloquea el servicio; el reprocesado completo de la base de perfiles es otra cosa y cuesta horas.
 */
public interface MigrateAlgorithmVersionUseCase {

  /** Cuántos pares se procesaron en esta pasada (recalculados, retirados o aplazados). */
  int execute();

  /**
   * Borra las fotos del puntaje anteriores al plazo de retención (24 meses por defecto, ADR-0017,
   * decisión 9) y devuelve cuántas. Se ejecuta con el mantenimiento, no en la ruta de una petición.
   */
  int purgeExpiredSnapshots();
}
