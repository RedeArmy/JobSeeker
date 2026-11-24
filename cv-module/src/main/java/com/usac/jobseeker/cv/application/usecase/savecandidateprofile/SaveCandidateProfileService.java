package com.usac.jobseeker.cv.application.usecase.savecandidateprofile;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateCollectionsReadPort;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileView;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileReadPort;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.CandidateCapabilityPort;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.CandidateCollections;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import java.time.Clock;
import java.util.List;
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
 * <p>Ni el log ni la métrica llevan datos del perfil: el contacto es personal (BR-DATA-01).
 */
public class SaveCandidateProfileService implements SaveCandidateProfileUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(SaveCandidateProfileService.class);
  private static final String USE_CASE = "save_candidate_profile";
  private static final String TRACE_ID_KEY = "traceId";

  private final CandidateProfileStorePort profiles;
  private final CandidateCollectionsReadPort collections;
  private final ProfessionalProfileReadPort professionalProfiles;
  private final CandidateCapabilityPort capabilities;
  private final DomainEventPublisherPort events;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public SaveCandidateProfileService(
      CandidateProfileStorePort profiles,
      CandidateCollectionsReadPort collections,
      ProfessionalProfileReadPort professionalProfiles,
      CandidateCapabilityPort capabilities,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics,
      Clock clock,
      Supplier<UUID> ids) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.collections = Objects.requireNonNull(collections, "collections");
    this.professionalProfiles =
        Objects.requireNonNull(professionalProfiles, "professionalProfiles");
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.events = Objects.requireNonNull(events, "events");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  @Override
  @Transactional
  public SaveCandidateProfileResult execute(SaveCandidateProfileCommand command) {
    // BR-ACCT-04: la capacidad se comprueba antes que los datos, para que quien no puede actuar no
    // sepa qué reglas incumpliría.
    if (!capabilities.isCandidate(command.accountId())) {
      metrics.count(USE_CASE, "forbidden");
      throw new ForbiddenCapabilityException();
    }
    CandidateDetails details = validated(command);
    Optional<CandidateProfile> current = profiles.findByAccountId(command.accountId());
    if (current.isPresent()
        && current.get().version() == command.expectedVersion()
        && current.get().details().equals(details)) {
      return unchanged(current.get());
    }
    CandidateProfile saved =
        current.isPresent()
            ? update(current.get(), details, command.expectedVersion())
            : create(command, details);
    publishUpdated(saved);
    String outcome = current.isPresent() ? "updated" : "created";
    metrics.count(USE_CASE, outcome);
    LOG.info("event=candidate_profile_saved outcome={} Perfil de candidato guardado", outcome);
    // Un perfil recién creado no tiene conjuntos: se evita una lectura que sabemos vacía.
    CandidateCollections sets =
        current.isEmpty() ? CandidateCollections.empty() : collections.findByProfileId(saved.id());
    return new SaveCandidateProfileResult(CandidateProfileView.of(saved, sets), current.isEmpty());
  }

  private CandidateDetails validated(SaveCandidateProfileCommand c) {
    try {
      return CandidateDetails.of(
          c.contactEmail(),
          c.contactPhone(),
          c.countryOfResidence(),
          c.desiredModality(),
          c.preferredWorkSchedule(),
          c.relocationWillingness(),
          c.salaryMin(),
          c.salaryMax(),
          c.salaryCurrency(),
          c.salaryPeriod());
    } catch (ValidationFailedException invalid) {
      metrics.count(USE_CASE, "invalid");
      throw invalid;
    }
  }

  // Guardar lo mismo que ya está, con la versión vigente, no cambia nada: no sube la versión ni
  // emite evento, así un reenvío o un doble clic no provoca recálculos de score (BR-MATCH-03).
  private SaveCandidateProfileResult unchanged(CandidateProfile current) {
    metrics.count(USE_CASE, "unchanged");
    LOG.info("event=candidate_profile_saved outcome=unchanged Perfil de candidato sin cambios");
    return new SaveCandidateProfileResult(
        CandidateProfileView.of(current, collections.findByProfileId(current.id())), false);
  }

  // La versión 0 es la de «aún no existe»: quien la envía cree estar creando, así que si ya hay un
  // perfil (o aparece uno a la vez) otro guardado llegó antes y se rechaza en vez de pisarlo.
  private CandidateProfile create(SaveCandidateProfileCommand command, CandidateDetails details) {
    if (command.expectedVersion() != 0) {
      throw stale();
    }
    // BR-ACCT-02: el perfil de candidato se construye sobre el profesional, que se crea primero.
    if (professionalProfiles.findByAccountId(command.accountId()).isEmpty()) {
      metrics.count(USE_CASE, "invalid");
      throw new ValidationFailedException(
          List.of(new FieldViolation("professionalProfile", "REQUIRED")));
    }
    CandidateProfile profile =
        CandidateProfile.create(
            new CandidateProfileId(ids.get()), command.accountId(), details, clock.instant());
    if (!profiles.insertIfAbsent(profile)) {
      throw stale();
    }
    return profile;
  }

  private CandidateProfile update(
      CandidateProfile current, CandidateDetails details, long expectedVersion) {
    if (current.version() != expectedVersion) {
      throw stale();
    }
    CandidateProfile edited = current.edit(details, clock.instant());
    if (!profiles.updateIfVersion(edited, expectedVersion)) {
      throw stale();
    }
    return edited;
  }

  private StaleStateException stale() {
    metrics.count(USE_CASE, "stale");
    return new StaleStateException();
  }

  private void publishUpdated(CandidateProfile profile) {
    events.publish(
        new OutboxEvent(
            ids.get(),
            ProfileUpdated.CANDIDATE_AGGREGATE_TYPE,
            profile.id().value(),
            profile.version(),
            ProfileUpdated.EVENT_TYPE,
            OutboxEvent.FIRST_EVENT_VERSION,
            ProfileUpdated.payload(
                profile.accountId(), ProfileUpdated.CHANGE_CANDIDATE, profile.version()),
            // Correlaciona el evento con la petición que lo originó (ADR-0016, NFR-OBS-01).
            MDC.get(TRACE_ID_KEY)));
  }
}
