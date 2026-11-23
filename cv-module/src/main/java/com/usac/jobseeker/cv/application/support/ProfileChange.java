package com.usac.jobseeker.cv.application.support;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfileCollectionsReadPort;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.CandidateCapabilityPort;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.ProfessionalProfileStorePort;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileCollections;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Lo que comparten las cuatro colecciones al editar un elemento: comprobar y avanzar la versión del
 * perfil, publicar el evento, contar el resultado y devolver el perfil completo. Concentrarlo aquí
 * evita repetir cuatro veces una regla que debe ser idéntica en todas (decisión 3 del diseño de
 * `cv`: editar un hijo incrementa la versión de la raíz).
 *
 * <p>Se llama dentro de la transacción del caso de uso: la versión, el elemento y el evento se
 * escriben juntos o ninguno (ADR-0016). La versión se avanza **antes** de escribir el elemento: esa
 * escritura toma el bloqueo de la fila del perfil y, si otra edición ya lo avanzó, falla sin tocar
 * nada.
 *
 * <p>Ni el log ni la métrica llevan datos del perfil (BR-DATA-01).
 */
public class ProfileChange {

  private static final Logger LOG = LoggerFactory.getLogger(ProfileChange.class);
  private static final String TRACE_ID_KEY = "traceId";

  private final ProfessionalProfileStorePort profiles;
  private final ProfileCollectionsReadPort collections;
  private final CandidateCapabilityPort capabilities;
  private final DomainEventPublisherPort events;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public ProfileChange(
      ProfessionalProfileStorePort profiles,
      ProfileCollectionsReadPort collections,
      CandidateCapabilityPort capabilities,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics,
      Clock clock,
      Supplier<UUID> ids) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.collections = Objects.requireNonNull(collections, "collections");
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.events = Objects.requireNonNull(events, "events");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  /** Una versión negativa no existe: 0 es «sin crear» y las guardadas empiezan en 1. */
  public static void requireValidVersion(long version) {
    if (version < 0) {
      throw new ValidationFailedException(List.of(new FieldViolation("version", "INVALID")));
    }
  }

  /** Un identificador nuevo para un elemento. */
  public ProfileItemId newItemId() {
    return new ProfileItemId(ids.get());
  }

  /**
   * Construye un elemento validándolo. Si no cumple las reglas se cuenta como `invalid` y se
   * propaga el error, antes de tocar la base de datos.
   */
  public <T> T validated(String useCase, Supplier<T> build) {
    try {
      return build.get();
    } catch (ValidationFailedException invalid) {
      metrics.count(useCase, "invalid");
      throw invalid;
    }
  }

  /** Falla con `TOO_MANY` si la colección ya está llena. */
  public void requireRoom(String useCase, String field, int current) {
    if (current >= ProfileCollections.MAX_PER_COLLECTION) {
      metrics.count(useCase, "invalid");
      throw new ValidationFailedException(List.of(new FieldViolation(field, "TOO_MANY")));
    }
  }

  /**
   * Aplica un cambio sobre un elemento de la colección y devuelve el perfil completo.
   *
   * @param useCase nombre estable del caso de uso, para la métrica
   * @param outcome resultado de éxito (`added`, `updated` o `removed`)
   * @param change tipo de cambio que lleva el evento
   * @param mutation lo que escribe el elemento; recibe el perfil dueño y lanza {@link
   *     NotFoundException} si el elemento no existe o no es de ese perfil
   */
  public ProfessionalProfileView apply(
      String useCase,
      String outcome,
      AccountId account,
      long expectedVersion,
      String change,
      Mutation mutation) {
    // Un perfil que aún no se ha creado no tiene nada que editar: para quien pregunta es igual a
    // que no exista, así que no se distingue de un elemento ajeno.
    ProfessionalProfile current =
        profiles
            .findByAccountId(account)
            .orElseThrow(() -> failed(useCase, "not_found", new NotFoundException()));
    if (current.version() != expectedVersion) {
      throw failed(useCase, "stale", new StaleStateException());
    }
    ProfessionalProfile touched = current.touched(clock.instant());
    if (!profiles.updateIfVersion(touched, expectedVersion)) {
      throw failed(useCase, "stale", new StaleStateException());
    }
    try {
      mutation.write(current.id());
    } catch (NotFoundException absent) {
      metrics.count(useCase, "not_found");
      throw absent;
    }
    // BR-MATCH-03 / BR-ACCT-06: solo una cuenta con capacidad de candidato tiene un score que
    // recalcular (domain-model.md §6).
    if (capabilities.isCandidate(account)) {
      publish(touched, change);
    }
    metrics.count(useCase, outcome);
    LOG.info(
        "event=professional_profile_collection_changed use_case={} outcome={} Colección del perfil modificada",
        useCase,
        outcome);
    return ProfessionalProfileView.of(touched, collections.findByProfileId(current.id()));
  }

  private <E extends RuntimeException> E failed(String useCase, String outcome, E error) {
    metrics.count(useCase, outcome);
    return error;
  }

  private void publish(ProfessionalProfile profile, String change) {
    events.publish(
        new OutboxEvent(
            ids.get(),
            ProfileUpdated.AGGREGATE_TYPE,
            profile.id().value(),
            profile.version(),
            ProfileUpdated.EVENT_TYPE,
            OutboxEvent.FIRST_EVENT_VERSION,
            ProfileUpdated.payload(profile.accountId(), change, profile.version()),
            MDC.get(TRACE_ID_KEY)));
  }

  /** Lo que escribe un caso de uso sobre el elemento, ya con la versión del perfil avanzada. */
  @FunctionalInterface
  public interface Mutation {
    void write(ProfessionalProfileId profile);
  }
}
