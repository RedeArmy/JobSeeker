package com.usac.jobseeker.cv.application.support;

import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.application.storage.FileDeletionRequested;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Pide borrar objetos del almacén por la bandeja (ADR-0021 §6), en la transacción de quien llama:
 * si esta se deshace, no se borra nada, y si el almacén falla, el consumidor reintenta. Lo
 * comparten la foto (al reemplazarla o quitarla) y la anonimización (CV y foto).
 */
public final class FileDeletionRequests {

  private static final String TRACE_ID_KEY = "traceId";

  private FileDeletionRequests() {}

  /** No publica nada si no hay claves. */
  public static void request(
      DomainEventPublisherPort events,
      Stamp stamp,
      String aggregateType,
      UUID aggregateId,
      long aggregateVersion,
      List<StoredObjectKey> keys) {
    if (keys.isEmpty()) {
      return;
    }
    events.publish(
        new OutboxEvent(
            stamp.ids().get(),
            aggregateType,
            aggregateId,
            aggregateVersion,
            FileDeletionRequested.EVENT_TYPE,
            OutboxEvent.FIRST_EVENT_VERSION,
            FileDeletionRequested.payload(keys),
            MDC.get(TRACE_ID_KEY)));
  }
}
