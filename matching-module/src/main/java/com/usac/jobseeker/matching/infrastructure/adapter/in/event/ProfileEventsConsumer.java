package com.usac.jobseeker.matching.infrastructure.adapter.in.event;

import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.embedentity.HandleProfileEventCommand;
import com.usac.jobseeker.matching.application.usecase.embedentity.HandleProfileEventUseCase;
import com.usac.jobseeker.matching.application.usecase.embedentity.ProfileEventKind;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recibe de la bandeja los eventos de `cv` que cambian lo que `matching` guarda de un perfil
 * (ADR-0016, decisión 12) y los entrega al caso de uso. Solo traduce: lee la cuenta de la carga y
 * el tipo de evento. Los eventos de disponibilidad y de exclusión no se atienden: nunca recalculan
 * un vector ni un score (BR-MATCH-07).
 *
 * <p>La carga solo trae identificadores (BR-DATA-01): el consumidor es de **nivel disparado** y lee
 * el estado actual por la lectura de `cv` cuando trabaja, no copia valores del evento. Es
 * idempotente por naturaleza (pedir un cálculo agrupado, o borrar lo que ya no está), así que no
 * necesita `processed_events`.
 */
public class ProfileEventsConsumer implements OutboxEventConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(ProfileEventsConsumer.class);
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  // Los tipos son el contrato publicado por `cv` (tdd/cv-module.md §10.5); se nombran aquí y no se
  // importan de `cv`, porque matching solo puede tocar `cv` desde su capa anticorrupción.
  private static final Map<String, ProfileEventKind> KINDS =
      Map.of(
          "profile.updated", ProfileEventKind.UPDATED,
          "candidate.discoverability_changed", ProfileEventKind.DISCOVERABILITY_CHANGED,
          "profile.anonymized", ProfileEventKind.ANONYMIZED);

  private final HandleProfileEventUseCase handler;

  public ProfileEventsConsumer(HandleProfileEventUseCase handler) {
    this.handler = handler;
  }

  @Override
  public String name() {
    return "matching-profile-events";
  }

  @Override
  public boolean handles(String eventType) {
    return KINDS.containsKey(eventType);
  }

  @Override
  public void consume(OutboxEvent event) {
    Optional<AccountId> account = accountOf(event.payload());
    if (account.isEmpty()) {
      // Una carga sin cuenta válida nunca mejorará con reintentos: se deja constancia y se da por
      // hecha.
      LOG.error("event=profile_event_invalid Evento de perfil sin una cuenta válida en su carga");
      return;
    }
    handler.execute(new HandleProfileEventCommand(KINDS.get(event.eventType()), account.get()));
  }

  private static Optional<AccountId> accountOf(String payload) {
    try {
      return Optional.of(
          AccountId.fromSubject(JSON.readTree(payload).path("accountId").asString()));
    } catch (IllegalArgumentException | tools.jackson.core.JacksonException _) {
      return Optional.empty();
    }
  }
}
