package com.usac.jobseeker.auth.application.usecase.handleclavariswebhook;

import com.usac.jobseeker.common.application.idempotency.ProcessedEventsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config`, sin estereotipos aquí (ADR-0015, ARCH-02).
 *
 * <p>Todo ocurre en **una sola transacción** (ADR-0016, decisión 11): marcar el evento como
 * procesado, aplicar el efecto en la base local y escribir en la bandeja lo que vive fuera de ella.
 * Si algo falla, no queda rastro: ni el evento marcado ni el efecto, y el reintento de Clavaris lo
 * aplica una vez.
 */
public class HandleClavarisWebhookService implements HandleClavarisWebhookUseCase {

  /** Nombre con el que este consumidor se registra en `processed_events`. */
  public static final String CONSUMER = "clavaris-webhook";

  private static final int FIRST_EVENT_VERSION = 1;

  private final ProcessedEventsPort processedEvents;
  private final IdentityStatePort identity;
  private final DomainEventPublisherPort publisher;

  public HandleClavarisWebhookService(
      ProcessedEventsPort processedEvents,
      IdentityStatePort identity,
      DomainEventPublisherPort publisher) {
    this.processedEvents = Objects.requireNonNull(processedEvents, "processedEvents");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
  }

  @Override
  @Transactional
  public HandleClavarisWebhookResult execute(HandleClavarisWebhookCommand command) {
    Optional<ClavarisEvent> event = ClavarisEvent.from(command.eventType());
    if (event.isEmpty()) {
      // Sin efecto: no se marca como procesado, no habría nada que deduplicar.
      return HandleClavarisWebhookResult.IGNORED;
    }
    if (!processedEvents.markProcessed(CONSUMER, command.eventId())) {
      return HandleClavarisWebhookResult.DUPLICATE;
    }
    if (!identity.accountExists(command.accountId())) {
      return HandleClavarisWebhookResult.UNKNOWN_ACCOUNT;
    }
    // El espejo respeta el orden de los eventos...
    event
        .get()
        .identityStatus()
        .ifPresent(
            status ->
                identity.applyIdentityEvent(
                    command.accountId(), status, command.occurredAt(), command.eventId()));
    // ...pero cerrar sesiones va siempre: es la dirección segura aunque el evento llegue viejo. Una
    // sesión cerrada de más obliga a iniciar sesión de nuevo; una abierta de menos es un acceso
    // que debía haberse cortado.
    if (event.get().closesSessions()) {
      publisher.publish(sessionsInvalidation(command, event.get()));
    }
    return HandleClavarisWebhookResult.APPLIED;
  }

  private static OutboxEvent sessionsInvalidation(
      HandleClavarisWebhookCommand command, ClavarisEvent reason) {
    return new OutboxEvent(
        UUID.randomUUID(),
        AccountSessionsInvalidationRequested.AGGREGATE_TYPE,
        command.accountId().value(),
        // Versión dentro del agregado: el instante del evento, que es lo que ordena (ADR-0016, 4).
        command.occurredAt().toEpochMilli(),
        AccountSessionsInvalidationRequested.EVENT_TYPE,
        FIRST_EVENT_VERSION,
        AccountSessionsInvalidationRequested.payload(
            command.accountId(), reason, command.eventId()),
        command.traceId());
  }
}
