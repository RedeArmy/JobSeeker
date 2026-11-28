package com.usac.jobseeker.ats.application.usecase.withdrawapplication;

import com.usac.jobseeker.ats.application.support.ActorCapabilityPort;
import com.usac.jobseeker.ats.application.support.ApplicationOutbox;
import com.usac.jobseeker.ats.application.support.ApplicationStorePort;
import com.usac.jobseeker.ats.application.usecase.listcandidateapplications.CandidateApplication;
import com.usac.jobseeker.ats.domain.event.ApplicationEvents;
import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.ats.domain.model.ChangeInitiator;
import com.usac.jobseeker.ats.domain.model.StatusChange;
import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.InvalidTransitionException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). La postulación de otro
 * candidato **no existe** para quien llama (regla 2 del TDD): `404`, no `403`. El cambio es un
 * compare-and-set sobre la versión leída, con su fila de historial y su evento en la misma
 * transacción.
 */
public class WithdrawApplicationService implements WithdrawApplicationUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(WithdrawApplicationService.class);
  private static final String USE_CASE = "withdraw_application";

  private final ActorCapabilityPort capabilities;
  private final ApplicationStorePort store;
  private final DomainEventPublisherPort events;
  private final UseCaseMetricsPort metrics;
  private final Supplier<UUID> ids;
  private final Clock clock;

  public WithdrawApplicationService(
      ActorCapabilityPort capabilities,
      ApplicationStorePort store,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics,
      Supplier<UUID> ids,
      Clock clock) {
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.store = Objects.requireNonNull(store, "store");
    this.events = Objects.requireNonNull(events, "events");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional
  public CandidateApplication execute(WithdrawApplicationCommand command) {
    if (!capabilities.isCandidate(command.candidate())) {
      throw failed("forbidden", new ForbiddenCapabilityException());
    }
    ApplicationId id = new ApplicationId(command.applicationId());
    Application current =
        store
            .findById(id)
            .filter(a -> a.candidateAccountId().equals(command.candidate()))
            .orElseThrow(() -> failed("not_found", new NotFoundException()));
    Application.Transition withdrawn;
    try {
      withdrawn = current.withdraw(ChangeInitiator.CANDIDATE, ids.get(), clock.instant());
    } catch (InvalidTransitionException finished) {
      throw failed("invalid_transition", finished);
    }
    if (!store.updateIfVersion(withdrawn.application(), withdrawn.change(), current.version())) {
      throw failed("stale", new StaleStateException());
    }
    events.publish(
        ApplicationOutbox.event(
            ids.get(), withdrawn.application(), ApplicationEvents.STATUS_CHANGED));
    metrics.count(USE_CASE, "withdrawn");
    LOG.info("event=application_status_changed use_case={} Postulación retirada", USE_CASE);
    List<StatusChange> history = store.historyOf(List.of(id)).getOrDefault(id, List.of());
    return CandidateApplication.of(withdrawn.application(), history);
  }

  private <E extends RuntimeException> E failed(String outcome, E error) {
    metrics.count(USE_CASE, outcome);
    return error;
  }
}
