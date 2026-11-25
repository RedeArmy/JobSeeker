package com.usac.jobseeker.matching.infrastructure.adapter.in.event;

import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import com.usac.jobseeker.matching.application.usecase.embedentity.HandleJobEventCommand;
import com.usac.jobseeker.matching.application.usecase.embedentity.HandleJobEventUseCase;
import com.usac.jobseeker.matching.application.usecase.embedentity.JobEventKind;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recibe de la bandeja los eventos de `job` que cambian lo que `matching` guarda de una vacante
 * (ADR-0016, decisión 12) y los entrega al caso de uso. Solo traduce: lee la vacante de la carga y
 * clasifica el tipo de evento.
 *
 * <p>La carga solo trae el identificador y la versión (BR-DATA-01): el consumidor es de **nivel
 * disparado** y lee el estado actual por la lectura de `job` cuando trabaja, sin copiar valores del
 * evento. Es idempotente por naturaleza (pedir un cálculo agrupado), así que no necesita
 * `processed_events`.
 */
public class JobEventsConsumer implements OutboxEventConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(JobEventsConsumer.class);
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  // Los tipos son el contrato publicado por `job` (`JobPostingEvents`, TDD de `job` §10.1); se
  // nombran aquí y no se importan de `job`, porque matching solo puede tocar `job` desde su capa
  // anticorrupción de salida.
  private static final Map<String, JobEventKind> KINDS =
      Map.of(
          "job.posting_updated", JobEventKind.CONTENT_UPDATED,
          "job.posting_published", JobEventKind.STATE_CHANGED,
          "job.posting_reopened", JobEventKind.STATE_CHANGED,
          "job.posting_closed", JobEventKind.STATE_CHANGED,
          "job.posting_archived", JobEventKind.STATE_CHANGED,
          "job.posting_deleted", JobEventKind.STATE_CHANGED,
          "job.recruiting_stage_changed", JobEventKind.STATE_CHANGED);

  private final HandleJobEventUseCase handler;

  public JobEventsConsumer(HandleJobEventUseCase handler) {
    this.handler = handler;
  }

  @Override
  public String name() {
    return "matching-job-events";
  }

  @Override
  public boolean handles(String eventType) {
    return KINDS.containsKey(eventType);
  }

  @Override
  public void consume(OutboxEvent event) {
    Optional<JobPostingId> job = jobOf(event.payload());
    if (job.isEmpty()) {
      // Una carga sin una vacante válida nunca mejorará con reintentos: se deja constancia y se da
      // por hecha.
      LOG.error(
          "event=job_event_invalid Evento de vacante sin un identificador válido en su carga");
      return;
    }
    handler.execute(new HandleJobEventCommand(KINDS.get(event.eventType()), job.get()));
  }

  private static Optional<JobPostingId> jobOf(String payload) {
    try {
      return Optional.of(
          new JobPostingId(
              UUID.fromString(JSON.readTree(payload).path("jobPostingId").asString())));
    } catch (IllegalArgumentException | tools.jackson.core.JacksonException _) {
      return Optional.empty();
    }
  }
}
