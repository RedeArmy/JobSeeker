package com.usac.jobseeker.ats.application.support;

import com.usac.jobseeker.ats.domain.event.ApplicationEvents;
import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * El evento de una postulación tal como viaja por la bandeja (ADR-0016): tipo de agregado, versión
 * y carga solo con identificadores y lo público (regla 8 del TDD). Lo comparten todos los casos de
 * uso que cambian una postulación, para que ninguno arme su carga a mano.
 */
public final class ApplicationOutbox {

  private static final String TRACE_ID_KEY = "traceId";

  private ApplicationOutbox() {}

  public static OutboxEvent event(UUID eventId, Application application, String eventType) {
    return new OutboxEvent(
        eventId,
        ApplicationEvents.AGGREGATE_TYPE,
        application.id().value(),
        application.version(),
        eventType,
        OutboxEvent.FIRST_EVENT_VERSION,
        ApplicationEvents.payload(application),
        MDC.get(TRACE_ID_KEY));
  }
}
