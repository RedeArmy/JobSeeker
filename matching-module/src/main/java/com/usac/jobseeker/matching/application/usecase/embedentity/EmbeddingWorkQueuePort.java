package com.usac.jobseeker.matching.application.usecase.embedentity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Puerto de salida de la cola de trabajos de embedding (TDD de matching §2, `matching_work_queue`).
 * Una fila por entidad (perfil o vacante): pedir un trabajo que ya existe lo **agrupa** en vez de
 * duplicarlo, y la generación impide perder una petición que llegó mientras el trabajo corría.
 */
public interface EmbeddingWorkQueuePort {

  /**
   * Un trabajo reclamado: su identidad, qué entidad calcula (y su clave: la cuenta de un perfil o
   * el identificador de una vacante), la generación que se vio, los intentos que lleva y desde
   * cuándo había una petición sin atender, que es lo que mide la frescura.
   */
  record Job(
      UUID id,
      EmbeddingEntity entity,
      UUID key,
      long generation,
      int attempts,
      Instant pendingSince) {}

  /**
   * Pide que se calcule la entidad. Si ya hay un trabajo pendiente conserva el inicio más temprano
   * (las ediciones no lo aplazan sin fin) y sube su generación.
   */
  void request(EmbeddingEntity entity, UUID key, Instant notBefore);

  /**
   * Reclama trabajos listos y los arrienda; cuenta el intento. Los que vencieron su arrendamiento
   * con los intentos agotados pasan a FAILED. Se llama en una transacción corta.
   */
  List<Job> claim(int limit, Duration lease, int maxAttempts);

  /**
   * Termina un trabajo: lo borra si nadie pidió otro mientras corría, y si alguien lo hizo lo
   * libera para que se vuelva a ejecutar con el estado nuevo.
   */
  void complete(Job job);

  /** Devuelve el trabajo a la cola para más tarde (el modelo no estaba disponible). */
  void retryAt(Job job, Instant notBefore);

  /** Lo deja para revisión: no se reintenta más. */
  void fail(Job job);

  /**
   * Devuelve un trabajo reclamado que no se llegó a ejecutar porque su arrendamiento estaba por
   * vencer: queda disponible enseguida. El adaptador real además le devuelve el intento, que no se
   * gastó; por defecto basta con reprogramarlo para ya.
   */
  default void release(Job job) {
    retryAt(job, Instant.EPOCH);
  }

  /** Descarta lo pendiente de una entidad (la anonimizaron: no hay nada que calcular). */
  void discard(EmbeddingEntity entity, UUID key);

  /**
   * ¿Hay un cambio de esta entidad esperando ser procesado? Lo usa la interfaz para decir
   * «actualizando…» en vez de mostrar un valor viejo como si fuera el vigente (BR-MATCH-03). Por
   * defecto no hay nada pendiente; el adaptador real consulta la cola.
   */
  default boolean isPending(EmbeddingEntity entity, UUID key) {
    return false;
  }
}
