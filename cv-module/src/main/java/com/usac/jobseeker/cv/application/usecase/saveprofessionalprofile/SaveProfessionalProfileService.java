package com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). El caso de uso es el límite
 * transaccional: el perfil y el evento de la bandeja se escriben juntos o ninguno (ADR-0016).
 *
 * <p>Ni el log ni la métrica llevan datos del perfil (BR-DATA-01).
 */
public class SaveProfessionalProfileService implements SaveProfessionalProfileUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(SaveProfessionalProfileService.class);
  private static final String USE_CASE = "save_professional_profile";
  private static final String TRACE_ID_KEY = "traceId";

  private final ProfessionalProfileStorePort profiles;
  private final CandidateCapabilityPort capabilities;
  private final DomainEventPublisherPort events;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public SaveProfessionalProfileService(
      ProfessionalProfileStorePort profiles,
      CandidateCapabilityPort capabilities,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics,
      Clock clock,
      Supplier<UUID> ids) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.events = Objects.requireNonNull(events, "events");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  @Override
  @Transactional
  public SaveProfessionalProfileResult execute(SaveProfessionalProfileCommand command) {
    ProfessionalIdentity identity = validated(command);
    Optional<ProfessionalProfile> current = profiles.findByAccountId(command.accountId());
    if (current.isPresent() && isUnchanged(current.get(), identity, command.expectedVersion())) {
      return unchanged(current.get());
    }
    ProfessionalProfile saved =
        current.isPresent()
            ? update(current.get(), identity, command.expectedVersion())
            : create(command, identity);
    // BR-MATCH-03 / BR-ACCT-06: solo una cuenta con capacidad de candidato tiene un score que
    // recalcular; para las demás el evento no tendría quien lo lea (domain-model.md §6).
    if (capabilities.isCandidate(command.accountId())) {
      publishUpdated(saved);
    }
    String outcome = current.isPresent() ? "updated" : "created";
    metrics.count(USE_CASE, outcome);
    LOG.info("event=professional_profile_saved outcome={} Perfil profesional guardado", outcome);
    return new SaveProfessionalProfileResult(ProfessionalProfileView.of(saved), current.isEmpty());
  }

  // Guardar lo mismo que ya está, con la versión vigente, no cambia nada: no sube la versión ni
  // emite evento, así un reenvío o un doble clic no provoca recálculos de score (BR-MATCH-03) ni
  // invalida la edición que otra pestaña esté haciendo.
  private static boolean isUnchanged(
      ProfessionalProfile current, ProfessionalIdentity identity, long expectedVersion) {
    return current.version() == expectedVersion && current.identity().equals(identity);
  }

  private SaveProfessionalProfileResult unchanged(ProfessionalProfile current) {
    metrics.count(USE_CASE, "unchanged");
    LOG.info("event=professional_profile_saved outcome=unchanged Perfil profesional sin cambios");
    return new SaveProfessionalProfileResult(ProfessionalProfileView.of(current), false);
  }

  private ProfessionalIdentity validated(SaveProfessionalProfileCommand command) {
    try {
      return ProfessionalIdentity.of(command.fullName(), command.headline(), command.bio());
    } catch (ValidationFailedException invalid) {
      metrics.count(USE_CASE, "invalid");
      throw invalid;
    }
  }

  // La versión 0 es la de «aún no existe»: quien la envía cree estar creando, así que si ya hay un
  // perfil (o aparece uno a la vez) otro guardado llegó antes y se rechaza en vez de pisarlo.
  private ProfessionalProfile create(
      SaveProfessionalProfileCommand command, ProfessionalIdentity identity) {
    if (command.expectedVersion() != 0) {
      throw stale();
    }
    ProfessionalProfile profile =
        ProfessionalProfile.create(
            new ProfessionalProfileId(ids.get()), command.accountId(), identity, clock.instant());
    if (!profiles.insertIfAbsent(profile)) {
      throw stale();
    }
    return profile;
  }

  private ProfessionalProfile update(
      ProfessionalProfile current, ProfessionalIdentity identity, long expectedVersion) {
    if (current.version() != expectedVersion) {
      throw stale();
    }
    ProfessionalProfile edited = current.edit(identity, clock.instant());
    if (!profiles.updateIfVersion(edited, expectedVersion)) {
      throw stale();
    }
    return edited;
  }

  private StaleStateException stale() {
    metrics.count(USE_CASE, "stale");
    return new StaleStateException();
  }

  private void publishUpdated(ProfessionalProfile profile) {
    events.publish(
        new OutboxEvent(
            ids.get(),
            ProfileUpdated.AGGREGATE_TYPE,
            profile.id().value(),
            profile.version(),
            ProfileUpdated.EVENT_TYPE,
            OutboxEvent.FIRST_EVENT_VERSION,
            ProfileUpdated.payload(
                profile.accountId(), ProfileUpdated.CHANGE_IDENTITY, profile.version()),
            // Correlaciona el evento con la petición que lo originó (ADR-0016, NFR-OBS-01); fuera
            // de
            // una petición no hay `traceId` y el evento viaja sin él.
            MDC.get(TRACE_ID_KEY)));
  }
}
