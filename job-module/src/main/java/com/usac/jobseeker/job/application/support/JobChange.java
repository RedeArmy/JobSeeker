package com.usac.jobseeker.job.application.support;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.common.domain.error.InvalidTransitionException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.PublishingDeniedException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.domain.event.JobPostingEvents;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobRequirement;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Lo que comparten los casos de uso que cambian una vacante propia: comprobar la capacidad,
 * localizarla como su dueño, comprobar la versión, escribirla con *compare-and-set*, publicar el
 * evento y contar el resultado. Se llama dentro de la transacción del caso de uso: la vacante, sus
 * requisitos y el evento se escriben juntos o ninguno (ADR-0016). Ni el log ni la métrica llevan
 * datos de la vacante ni del reclutador (BR-DATA-01).
 */
public class JobChange {

  private static final Logger LOG = LoggerFactory.getLogger(JobChange.class);
  private static final String TRACE_ID_KEY = "traceId";
  private static final String OUTCOME_STALE = "stale";

  private final JobPostingStorePort postings;
  private final JobRequirementsStorePort requirements;
  private final CapabilityPort capabilities;
  private final DomainEventPublisherPort events;
  private final UseCaseMetricsPort metrics;
  private final Supplier<UUID> ids;

  public JobChange(
      JobPostingStorePort postings,
      JobRequirementsStorePort requirements,
      CapabilityPort capabilities,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics,
      Supplier<UUID> ids) {
    this.postings = Objects.requireNonNull(postings, "postings");
    this.requirements = Objects.requireNonNull(requirements, "requirements");
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.events = Objects.requireNonNull(events, "events");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  /** Una versión negativa no existe: las guardadas empiezan en 1. */
  public static void requireValidVersion(long version) {
    if (version < 0) {
      throw new ValidationFailedException(List.of(new FieldViolation("version", "INVALID")));
    }
  }

  /** Falla con 403 si la cuenta no es reclutadora; se comprueba antes que nada (BR-JOB-01). */
  public void requireRecruiter(String useCase, AccountId account) {
    if (!capabilities.isRecruiter(account)) {
      throw failed(useCase, "forbidden", new ForbiddenCapabilityException());
    }
  }

  /**
   * La vacante de la cuenta, o 404 si no existe, está eliminada o es de otro: una ajena **no
   * existe** para quien no es su dueño, y no se confirma que exista (TDD de job §5, regla 2).
   */
  public JobPosting requireOwned(String useCase, AccountId account, JobPostingId id) {
    return postings
        .findById(id)
        .filter(posting -> posting.isOwnedBy(account))
        .orElseThrow(() -> failed(useCase, "not_found", new NotFoundException()));
  }

  /** Falla con 409 si la versión que el cliente leyó ya no es la vigente. */
  public void requireVersion(String useCase, JobPosting current, long expectedVersion) {
    if (current.version() != expectedVersion) {
      throw failed(useCase, OUTCOME_STALE, new StaleStateException());
    }
  }

  /**
   * Ejecuta una operación del dominio y cuenta por qué falla, sin cambiar el error: un dato
   * inválido es `invalid`, un par de estados que no existe es `invalid_transition` y una operación
   * que no aplica al estado es `invalid_state`.
   */
  public <T> T guarded(String useCase, Supplier<T> operation) {
    try {
      return operation.get();
    } catch (ValidationFailedException invalid) {
      metrics.count(useCase, "invalid");
      throw invalid;
    } catch (InvalidTransitionException invalid) {
      metrics.count(useCase, "invalid_transition");
      throw invalid;
    } catch (InvalidStateException invalid) {
      metrics.count(useCase, "invalid_state");
      throw invalid;
    } catch (PublishingDeniedException denied) {
      metrics.count(useCase, "denied_" + denied.reason().name().toLowerCase(Locale.ROOT));
      throw denied;
    }
  }

  /** Escribe la vacante con *compare-and-set*; si otra edición se adelantó, es 409. */
  public void save(String useCase, JobPosting posting, long expectedVersion) {
    if (!postings.updateIfVersion(posting, expectedVersion)) {
      throw failed(useCase, OUTCOME_STALE, new StaleStateException());
    }
  }

  /** Los requisitos guardados de la vacante. */
  public List<JobRequirement> requirementsOf(JobPostingId id) {
    return requirements.findByPosting(id);
  }

  /** Cuenta un resultado y deja una línea de log sin datos. */
  public void done(String useCase, String outcome) {
    metrics.count(useCase, outcome);
    LOG.info(
        "event=job_posting_changed use_case={} outcome={} Vacante modificada", useCase, outcome);
  }

  /** Publica un evento de la vacante en la bandeja, dentro de la transacción en curso. */
  public void publish(JobPosting posting, String eventType) {
    events.publish(
        new OutboxEvent(
            ids.get(),
            JobPostingEvents.AGGREGATE_TYPE,
            posting.id().value(),
            posting.version(),
            eventType,
            OutboxEvent.FIRST_EVENT_VERSION,
            JobPostingEvents.payload(posting.id(), posting.version()),
            MDC.get(TRACE_ID_KEY)));
  }

  /** Cuenta un fallo y devuelve el error para lanzarlo. */
  public <E extends RuntimeException> E failed(String useCase, String outcome, E error) {
    metrics.count(useCase, outcome);
    return error;
  }
}
