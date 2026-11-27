package com.usac.jobseeker.cv.application.usecase.reconcileorphancvfiles;

/**
 * Borra del almacén los archivos de `cv` (versiones del CV y fotos de perfil) que ya no referencia
 * nadie (TD-041, ADR-0021 §2 y §6): un alta que falló al confirmar, o un borrado por anonimización
 * o reemplazo que agotó sus reintentos. Lo dispara un planificador; ejecutarlo dos veces a la vez
 * no hace daño, porque borrar es idempotente.
 *
 * <p>Lanza {@code UpstreamUnavailableException} si el almacén no responde: la pasada se repite en
 * la siguiente ejecución.
 */
public interface ReconcileOrphanCvFilesUseCase {

  Result execute();

  /**
   * @param scanned objetos examinados en esta pasada
   * @param orphans huérfanos encontrados con la edad suficiente
   * @param deleted los que se borraron (0 si se frenó)
   * @param halted si se frenó por encontrar más huérfanos que el límite
   */
  record Result(int scanned, int orphans, int deleted, boolean halted) {}
}
