package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Puerto de salida de la cola de trabajos de embedding (TDD de matching §2, `matching_work_queue`).
 * Una fila por cuenta: pedir un trabajo que ya existe lo **agrupa** en vez de duplicarlo, y la
 * generación impide perder una petición que llegó mientras el trabajo corría.
 */
public interface EmbeddingWorkQueuePort {

  /**
   * Un trabajo reclamado: su identidad, la generación que se vio, los intentos que lleva y desde
   * cuándo había una petición sin atender, que es lo que mide la frescura.
   */
  record Job(UUID id, AccountId account, long generation, int attempts, Instant pendingSince) {}

  /**
   * Pide que se calcule el perfil de la cuenta. Si ya hay un trabajo pendiente conserva el inicio
   * más temprano (las ediciones no lo aplazan sin fin) y sube su generación.
   */
  void request(AccountId account, Instant notBefore);

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

  /** Descarta lo pendiente de una cuenta (la anonimizaron: no hay nada que calcular). */
  void discard(AccountId account);
}
