package com.usac.jobseeker.common.application.outbox;

/**
 * Quien reacciona a un evento de la bandeja (ADR-0016, decisión 12). Cada módulo aporta los suyos y
 * el sondeador, alojado en `app`, los descubre; un módulo no importa los consumidores de otro.
 *
 * <p>La entrega es **al menos una vez**: {@link #consume} debe ser idempotente, y puede lanzar una
 * excepción para que se reintente con retroceso. Los consumidores son de nivel disparado: relee el
 * estado actual en vez de fiarse del orden de llegada.
 */
public interface OutboxEventConsumer {

  /** Nombre estable del consumidor, para métricas y registros. */
  String name();

  boolean handles(String eventType);

  void consume(OutboxEvent event);
}
