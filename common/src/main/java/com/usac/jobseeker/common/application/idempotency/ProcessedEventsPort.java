package com.usac.jobseeker.common.application.idempotency;

import java.util.UUID;

/**
 * Registro de eventos ya procesados (ADR-0016, decisiones 3 y 11), para que quien reciba un evento
 * más de una vez lo aplique una sola.
 *
 * <p>La implementación se apoya en una restricción única (`INSERT … ON CONFLICT DO NOTHING`) y no
 * en una consulta previa: con la consulta, 8 de 385 eventos se aplicaron dos veces bajo
 * concurrencia. Debe llamarse dentro de la misma transacción que el efecto, de modo que si el
 * efecto falla el evento sigue siendo reintentable y nunca se pierde.
 */
public interface ProcessedEventsPort {

  /**
   * Marca el evento como procesado por este consumidor.
   *
   * @return `true` si es la primera vez y hay que aplicar el efecto; `false` si ya estaba
   */
  boolean markProcessed(String consumer, UUID eventId);
}
