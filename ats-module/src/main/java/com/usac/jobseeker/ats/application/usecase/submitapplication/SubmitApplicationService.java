package com.usac.jobseeker.ats.application.usecase.submitapplication;

import com.usac.jobseeker.ats.application.support.ApplicationSnapshotPort;
import com.usac.jobseeker.ats.application.support.ApplicationStorePort;
import com.usac.jobseeker.ats.application.support.CandidateCapabilityPort;
import com.usac.jobseeker.ats.domain.event.ApplicationEvents;
import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.common.application.idempotency.IdempotencyKeysPort;
import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.error.ApplicationDeniedException;
import com.usac.jobseeker.common.domain.error.ApplicationRequirementsMissingException;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Postular es una sola
 * transacción con un orden fijo (regla 3 del TDD): validar la vacante, la preparación y el
 * duplicado; insertar la postulación con su historial; pedir a `matching` la copia del perfil;
 * publicar el evento. Si cualquier paso falla no queda nada: ni postulación sin foto, ni evento sin
 * postulación, ni clave de idempotencia gastada.
 */
public class SubmitApplicationService implements SubmitApplicationUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(SubmitApplicationService.class);
  private static final String USE_CASE = "submit_application";
  private static final String TRACE_ID_KEY = "traceId";

  private final Ports ports;
  private final UseCaseMetricsPort metrics;
  private final Supplier<UUID> ids;
  private final Clock clock;

  /** Los puertos de salida, agrupados para que el constructor siga siendo legible. */
  public record Ports(
      CandidateCapabilityPort capabilities,
      IdempotencyKeysPort idempotency,
      JobApplicationGatePort gate,
      ApplicantReadinessPort readiness,
      ApplicationStorePort store,
      ApplicationSnapshotPort snapshots,
      DomainEventPublisherPort events) {

    public Ports {
      Objects.requireNonNull(capabilities, "capabilities");
      Objects.requireNonNull(idempotency, "idempotency");
      Objects.requireNonNull(gate, "gate");
      Objects.requireNonNull(readiness, "readiness");
      Objects.requireNonNull(store, "store");
      Objects.requireNonNull(snapshots, "snapshots");
      Objects.requireNonNull(events, "events");
    }
  }

  public SubmitApplicationService(
      Ports ports, UseCaseMetricsPort metrics, Supplier<UUID> ids, Clock clock) {
    this.ports = Objects.requireNonNull(ports, "ports");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional
  public SubmittedApplication execute(SubmitApplicationCommand command) {
    if (!ports.capabilities().isCandidate(command.candidate())) {
      throw failed("forbidden", new ForbiddenCapabilityException());
    }
    // La clave se reserva antes de cualquier efecto: un reintento simultáneo espera aquí a que este
    // confirme y recibe la misma postulación (regla 5 del TDD).
    if (command.idempotencyKey() != null) {
      Optional<String> earlier =
          ports
              .idempotency()
              .claim(command.candidate(), command.idempotencyKey(), requestHash(command));
      if (earlier.isPresent()) {
        return replay(earlier.get());
      }
    }
    ApplicantReadinessPort.Readiness ready = checkAll(command);
    UUID profile = ready.candidateProfileId().orElseThrow();
    Application.Transition submitted =
        Application.submit(
            new ApplicationId(ids.get()),
            profile,
            command.candidate(),
            command.jobPostingId(),
            ready.cvFileId().orElseThrow(),
            ids.get(),
            clock.instant());
    // BR-TRACK-01 lo impone el índice único, no la lectura previa: si otro envío ganó la carrera
    // entre la comprobación y aquí, este no escribe nada.
    if (!ports.store().insertIfNoneOpen(submitted.application(), submitted.change())) {
      throw failed(
          "already_applied",
          new ApplicationDeniedException(ApplicationDeniedException.Reason.ALREADY_APPLIED));
    }
    Application application = submitted.application();
    ports
        .snapshots()
        .snapshotOnSubmit(command.candidate(), command.jobPostingId(), application.id());
    publish(application);
    if (command.idempotencyKey() != null) {
      ports
          .idempotency()
          .complete(command.candidate(), command.idempotencyKey(), application.id().toString());
    }
    metrics.count(USE_CASE, "submitted");
    LOG.info("event=application_submitted use_case={} Postulación enviada", USE_CASE);
    return SubmittedApplication.of(application);
  }

  // La vacante primero: si no existe para el candidato, no importa qué le falte a su perfil.
  private ApplicantReadinessPort.Readiness checkAll(SubmitApplicationCommand command) {
    switch (ports.gate().check(command.candidate(), command.jobPostingId())) {
      case NOT_FOUND -> throw failed("not_found", new NotFoundException());
      case OWN_JOB ->
          throw failed(
              "self_application",
              new ApplicationDeniedException(ApplicationDeniedException.Reason.SELF_APPLICATION));
      case NOT_ACCEPTING -> throw failed("not_accepting", new InvalidStateException());
      case OPEN -> {
        // Sigue con la preparación del candidato.
      }
    }
    ApplicantReadinessPort.Readiness ready = ports.readiness().check(command.candidate());
    if (!ready.ready()) {
      throw failed(
          "requirements_missing", new ApplicationRequirementsMissingException(ready.missing()));
    }
    // La lectura previa solo da un mensaje claro en el caso común; la garantía es el índice.
    if (ports
        .store()
        .hasOpenApplication(ready.candidateProfileId().orElseThrow(), command.jobPostingId())) {
      throw failed(
          "already_applied",
          new ApplicationDeniedException(ApplicationDeniedException.Reason.ALREADY_APPLIED));
    }
    return ready;
  }

  // Lo guardado es el identificador; se devuelve la postulación como está ahora.
  private SubmittedApplication replay(String storedId) {
    Application application =
        ports
            .store()
            .findById(new ApplicationId(UUID.fromString(storedId)))
            .orElseThrow(
                () -> new IllegalStateException("La clave apunta a una postulación inexistente"));
    metrics.count(USE_CASE, "replayed");
    return SubmittedApplication.of(application);
  }

  private void publish(Application application) {
    ports
        .events()
        .publish(
            new OutboxEvent(
                ids.get(),
                ApplicationEvents.AGGREGATE_TYPE,
                application.id().value(),
                application.version(),
                ApplicationEvents.SUBMITTED,
                OutboxEvent.FIRST_EVENT_VERSION,
                ApplicationEvents.payload(application),
                MDC.get(TRACE_ID_KEY)));
  }

  // La huella distingue la operación y la vacante: la misma clave para otra vacante es un error
  // del cliente, no una repetición.
  private static String requestHash(SubmitApplicationCommand command) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((USE_CASE + ":" + command.jobPostingId()).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      // Toda JVM trae SHA-256 (especificación de MessageDigest).
      throw new IllegalStateException(impossible);
    }
  }

  private <E extends RuntimeException> E failed(String outcome, E error) {
    metrics.count(USE_CASE, outcome);
    return error;
  }
}
