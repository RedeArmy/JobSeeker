package com.usac.jobseeker.matching.infrastructure.adapter.in.job;

import com.usac.jobseeker.matching.application.usecase.embedentity.ProcessEmbeddingQueueUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Gancho de planificación del trabajador de embeddings: solo programa, la lógica es del caso de
 * uso. Repite pasadas mientras haya trabajo, con un tope, para vaciar una ráfaga sin esperar al
 * siguiente sondeo ni monopolizar el hilo. Corre en su propio hilo del planificador: una llamada
 * lenta a Ollama no retrasa el despacho de la bandeja (TD-002).
 */
public class EmbeddingWorker {

  private static final Logger LOG = LoggerFactory.getLogger(EmbeddingWorker.class);
  private static final int MAX_PASSES_PER_TICK = 20;

  private final ProcessEmbeddingQueueUseCase queue;

  public EmbeddingWorker(ProcessEmbeddingQueueUseCase queue) {
    this.queue = queue;
  }

  // Una pasada fallida no debe cancelar las siguientes de la planificación.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  @Scheduled(fixedDelayString = "${jobseeker.matching.queue.poll-interval:2s}")
  void run() {
    try {
      for (int pass = 0; pass < MAX_PASSES_PER_TICK; pass++) {
        if (queue.execute() == 0) {
          return;
        }
      }
    } catch (RuntimeException failure) {
      LOG.error(
          "event=embedding_worker_failed Falló una pasada del trabajador de embeddings", failure);
    }
  }
}
