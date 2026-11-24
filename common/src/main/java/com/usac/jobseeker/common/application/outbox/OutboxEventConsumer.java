package com.usac.jobseeker.common.application.outbox;

/**
 * Quien reacciona a un evento de la bandeja (ADR-0016, decisión 12). Cada módulo aporta los suyos y
 * el sondeador, alojado en `app`, los descubre; un módulo no importa los consumidores de otro.
 *
 * <p>Lo que el sondeador garantiza y lo que exige a quien consuma (ADR-0016, enmienda de
 * 24-nov-2026):
 *
 * <ul>
 *   <li>La entrega es **al menos una vez**: {@link #consume} debe ser idempotente, y puede lanzar
 *       una excepción para que se reintente con retroceso.
 *   <li>Se llama **sin transacción abierta** y sin bloqueos de fila: puede tardar (una llamada a
 *       Ollama) sin retener una conexión del pool. Si necesita atomicidad con la marca de
 *       `processed_events`, abre su propia transacción.
 *   <li>Nunca corren a la vez dos manejadores de eventos del **mismo agregado**, y entre los
 *       disponibles va primero el de menor versión. Un evento que espera su reintento no bloquea a
 *       los siguientes, así que puede llegar después de uno más nuevo.
 *   <li>Por eso el consumidor es de **nivel disparado**: relee el estado actual en vez de copiar
 *       valores de la carga (que solo lleva identificadores) o de fiarse del orden de llegada. Un
 *       evento viejo reprocesado no revierte nada.
 *   <li>Su {@link #name} es la clave de deduplicación en `processed_events`: debe ser estable, no
 *       vacío y distinto del de cualquier otro consumidor.
 * </ul>
 */
public interface OutboxEventConsumer {

  /** Nombre estable del consumidor, para métricas y registros. */
  String name();

  boolean handles(String eventType);

  void consume(OutboxEvent event);
}
