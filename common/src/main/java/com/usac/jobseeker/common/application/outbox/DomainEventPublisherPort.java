package com.usac.jobseeker.common.application.outbox;

/**
 * Puerto de salida para publicar un evento de dominio (ADR-0016, decisión 1). La implementación
 * escribe en la bandeja en la **misma transacción** del caso de uso que lo llama: nunca publica
 * fuera de ella, y el dominio nunca habla con la bandeja.
 */
public interface DomainEventPublisherPort {

  void publish(OutboxEvent event);
}
