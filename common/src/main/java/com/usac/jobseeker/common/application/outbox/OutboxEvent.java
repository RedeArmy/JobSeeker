package com.usac.jobseeker.common.application.outbox;

import java.util.Objects;
import java.util.UUID;

/**
 * Un evento de dominio tal como viaja por la bandeja de salida (ADR-0016). Lleva solo
 * identificadores y metadatos mínimos: el contenido (`payload`, un JSON) **nunca** lleva datos
 * personales (BR-DATA-01); quien lo consume lee el dato que necesite desde su propio puerto.
 *
 * @param aggregateVersion orden dentro de un agregado: no hay orden global garantizado
 * @param traceId correlación con el log estructurado; puede faltar
 */
public record OutboxEvent(
    UUID id,
    String aggregateType,
    UUID aggregateId,
    long aggregateVersion,
    String eventType,
    int eventVersion,
    String payload,
    String traceId) {

  /** La versión de esquema de un evento empieza en 1. */
  public static final int FIRST_EVENT_VERSION = 1;

  public OutboxEvent {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(aggregateType, "aggregateType");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(payload, "payload");
    if (aggregateType.isBlank() || eventType.isBlank()) {
      throw new IllegalArgumentException("aggregateType y eventType no pueden estar vacíos");
    }
    if (eventVersion < FIRST_EVENT_VERSION) {
      throw new IllegalArgumentException("eventVersion empieza en 1");
    }
  }
}
