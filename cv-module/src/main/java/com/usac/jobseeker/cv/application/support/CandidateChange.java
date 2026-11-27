package com.usac.jobseeker.cv.application.support;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateCollectionsReadPort;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileView;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.CandidateProfileStorePort;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.CandidateCapabilityPort;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.CandidateCollections;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Lo que comparten los tres conjuntos del perfil de candidato al reemplazarse: comprobar la
 * capacidad, comprobar y avanzar la versión, escribir el conjunto, publicar el evento, contar el
 * resultado y devolver el perfil completo. Es el equivalente de {@link ProfileChange} para el
 * perfil de candidato, que tiene su propia versión.
 *
 * <p>Se llama dentro de la transacción del caso de uso: la versión, el conjunto y el evento se
 * escriben juntos o ninguno (ADR-0016). La versión se avanza **antes** de escribir el conjunto, de
 * modo que si otra edición ya la avanzó, esta falla sin tocar nada. Ni el log ni la métrica llevan
 * datos del perfil (BR-DATA-01).
 */
public class CandidateChange {

  private static final Logger LOG = LoggerFactory.getLogger(CandidateChange.class);
  private static final String TRACE_ID_KEY = "traceId";
  private static final String OUTCOME_STALE = "stale";

  private final CandidateProfileStorePort profiles;
  private final CandidateCollectionsReadPort collections;
  private final CandidateCapabilityPort capabilities;
  private final DomainEventPublisherPort events;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public CandidateChange(
      CandidateProfileStorePort profiles,
      CandidateCollectionsReadPort collections,
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

  /** Falla con 403 si la cuenta no tiene la capacidad de candidato; se comprueba antes que nada. */
  public void requireCandidate(String useCase, AccountId account) {
    if (!capabilities.isCandidate(account)) {
      throw failed(useCase, "forbidden", new ForbiddenCapabilityException());
    }
  }

  /** Una versión negativa no existe: 0 es «sin crear» y las guardadas empiezan en 1. */
  public static void requireValidVersion(long version) {
    ChangeRules.requireValidVersion(version);
  }

  /** Construye el conjunto validándolo; si no cumple las reglas se cuenta como `invalid`. */
  public <T> T validated(String useCase, Supplier<T> build) {
    return ChangeRules.validated(metrics, useCase, build);
  }

  /**
   * Reemplaza un conjunto del perfil y devuelve el perfil completo.
   *
   * <p>Precondición: el llamador ya comprobó la capacidad con {@link #requireCandidate}, antes de
   * validar los datos, para que quien no puede actuar no sepa qué reglas incumpliría (BR-ACCT-04).
   * No se repite aquí: era una consulta idéntica a `accounts` por cada escritura.
   *
   * @param change tipo de cambio que lleva el evento
   * @param unchanged dice si el conjunto pedido es igual al que ya está guardado; si lo es, no se
   *     avanza la versión ni se emite evento, para que un reenvío no provoque recálculos del score
   *     (BR-MATCH-03)
   * @param mutation lo que escribe el conjunto; recibe el perfil dueño
   */
  public CandidateProfileView apply(
      String useCase,
      AccountId account,
      long expectedVersion,
      String change,
      Predicate<CandidateCollections> unchanged,
      Mutation mutation) {
    // Sin perfil de candidato no hay conjunto que reemplazar; para quien pregunta es lo mismo que
    // un recurso inexistente.
    CandidateProfile current = requireProfile(useCase, account);
    if (current.version() != expectedVersion) {
      throw failed(useCase, OUTCOME_STALE, new StaleStateException());
    }
    CandidateCollections stored = collections.findByProfileId(current.id());
    if (unchanged.test(stored)) {
      metrics.count(useCase, "unchanged");
      LOG.info(
          "event=candidate_set_replaced use_case={} outcome=unchanged Conjunto sin cambios",
          useCase);
      return CandidateProfileView.of(current, stored);
    }
    CandidateProfile touched = current.touched(clock.instant());
    if (!profiles.updateIfVersion(touched, expectedVersion)) {
      throw failed(useCase, OUTCOME_STALE, new StaleStateException());
    }
    mutation.write(current.id());
    // BR-MATCH-03 / BR-ACCT-06: el perfil cambió y lo que dependa de él se recalcula.
    publish(touched, change);
    metrics.count(useCase, "replaced");
    LOG.info(
        "event=candidate_set_replaced use_case={} outcome=replaced Conjunto del perfil de candidato reemplazado",
        useCase);
    return CandidateProfileView.of(touched, collections.findByProfileId(current.id()));
  }

  private <E extends RuntimeException> E failed(String useCase, String outcome, E error) {
    return ChangeRules.failed(metrics, useCase, outcome, error);
  }

  private void publish(CandidateProfile profile, String change) {
    publishEvent(
        profile,
        ProfileUpdated.EVENT_TYPE,
        ProfileUpdated.payload(profile.accountId(), change, profile.version()));
  }

  /** Publica un evento del perfil de candidato en la bandeja, dentro de la transacción en curso. */
  public void publishEvent(CandidateProfile profile, String eventType, String payload) {
    events.publish(
        new OutboxEvent(
            ids.get(),
            ProfileUpdated.CANDIDATE_AGGREGATE_TYPE,
            profile.id().value(),
            profile.version(),
            eventType,
            OutboxEvent.FIRST_EVENT_VERSION,
            payload,
            MDC.get(TRACE_ID_KEY)));
  }

  /** El perfil de candidato de la cuenta, o 404 si aún no lo creó (se cuenta como `not_found`). */
  public CandidateProfile requireProfile(String useCase, AccountId account) {
    return profiles
        .findByAccountId(account)
        .orElseThrow(() -> failed(useCase, "not_found", new NotFoundException()));
  }

  /**
   * Lo que cambia un dato propio de la fila del perfil (descubribilidad, disponibilidad).
   *
   * @param edit produce la versión siguiente del perfil con el cambio, a la hora que se le da
   * @param alreadyApplied dice si el perfil ya tiene el valor pedido
   * @param write escribe la fila con la versión esperada; `false` si otra edición se adelantó
   * @param eventType tipo del evento que se publica
   * @param payload carga del evento, solo identificadores
   */
  public record ProfileEdit(
      BiFunction<CandidateProfile, Instant, CandidateProfile> edit,
      Predicate<CandidateProfile> alreadyApplied,
      BiPredicate<CandidateProfile, Long> write,
      String eventType,
      Function<CandidateProfile, String> payload) {}

  /**
   * Cambia un dato propio de la fila del perfil y devuelve el perfil completo. Mismo contrato que
   * {@link #apply}: capacidad, versión esperada, «sin cambios» sin versión ni evento, y evento en
   * la misma transacción.
   */
  public CandidateProfileView editProfile(
      String useCase, AccountId account, long expectedVersion, ProfileEdit change) {
    requireCandidate(useCase, account);
    CandidateProfile current = requireProfile(useCase, account);
    if (current.version() != expectedVersion) {
      throw failed(useCase, OUTCOME_STALE, new StaleStateException());
    }
    if (change.alreadyApplied().test(current)) {
      metrics.count(useCase, "unchanged");
      LOG.info(
          "event=candidate_profile_changed use_case={} outcome=unchanged Sin cambios", useCase);
      return CandidateProfileView.of(current, collections.findByProfileId(current.id()));
    }
    CandidateProfile changed = change.edit().apply(current, clock.instant());
    if (!change.write().test(changed, expectedVersion)) {
      throw failed(useCase, OUTCOME_STALE, new StaleStateException());
    }
    publishEvent(changed, change.eventType(), change.payload().apply(changed));
    metrics.count(useCase, "changed");
    LOG.info(
        "event=candidate_profile_changed use_case={} outcome=changed Perfil de candidato modificado",
        useCase);
    return CandidateProfileView.of(changed, collections.findByProfileId(changed.id()));
  }

  /** Lo que escribe un caso de uso sobre el conjunto, ya con la versión del perfil avanzada. */
  @FunctionalInterface
  public interface Mutation {
    void write(CandidateProfileId profile);
  }
}
