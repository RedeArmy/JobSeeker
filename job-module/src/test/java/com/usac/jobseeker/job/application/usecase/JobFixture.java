package com.usac.jobseeker.job.application.usecase;

import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import com.usac.jobseeker.job.application.support.CapabilityPort;
import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.application.support.JobDetailsInput;
import com.usac.jobseeker.job.application.support.JobPostingStorePort;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.application.support.JobRequirementsStorePort;
import com.usac.jobseeker.job.application.support.PublishingGate;
import com.usac.jobseeker.job.application.support.PublishingPolicy;
import com.usac.jobseeker.job.application.usecase.createjobposting.CreateJobPostingCommand;
import com.usac.jobseeker.job.application.usecase.createjobposting.CreateJobPostingService;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobRequirement;
import com.usac.jobseeker.job.domain.model.JobStatus;
import com.usac.jobseeker.job.domain.model.WorkModality;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Los dobles en memoria que comparten las pruebas de los casos de uso de `job`: un almacén que
 * respeta el *compare-and-set*, capacidades configurables, una bandeja y un contador de métricas
 * que guardan lo que se les entrega, y un reloj que avanza solo cuando la prueba lo pide.
 */
public final class JobFixture {

  public static final Instant START = Instant.parse("2026-11-24T10:00:00Z");

  public final Store store = new Store();
  public final Capabilities capabilities = new Capabilities();
  public final Map<AccountId, RecruiterVerification> standings = new HashMap<>();
  public final List<OutboxEvent> events = new ArrayList<>();
  public final List<String> metrics = new ArrayList<>();
  public final AccountId owner = new AccountId(UUID.randomUUID());
  public final AccountId stranger = new AccountId(UUID.randomUUID());

  private final AtomicLong tick = new AtomicLong();
  private final Clock clock =
      new Clock() {
        @Override
        public java.time.ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return START.plusSeconds(tick.get());
        }
      };

  public final JobChange change =
      new JobChange(
          store,
          store,
          capabilities,
          (DomainEventPublisherPort) events::add,
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome),
          UUID::randomUUID);

  /**
   * Por defecto verificadas y con tope alto, para que las pruebas que no son del tope no lo noten.
   */
  public final PublishingPolicy policy = new PublishingPolicy(1, 50);

  public final PublishingGate gate =
      new PublishingGate(
          account -> standings.getOrDefault(account, RecruiterVerification.VERIFIED),
          store,
          policy);

  public JobFixture() {
    capabilities.recruiters.add(owner);
    capabilities.recruiters.add(stranger);
  }

  public Clock clock() {
    return clock;
  }

  /** Avanza el reloj para distinguir el momento de cada cambio. */
  public void advance(long seconds) {
    tick.addAndGet(seconds);
  }

  public static JobDetailsInput input(String title) {
    return new JobDetailsInput(
        title, "Descripción de la vacante", WorkModality.REMOTE, "GT", null, null, null, null);
  }

  /** Crea una vacante en borrador del dueño por el caso de uso real. */
  public JobPostingView draft() {
    return new CreateJobPostingService(store, change, clock, UUID::randomUUID)
        .execute(new CreateJobPostingCommand(owner, input("Backend")));
  }

  public List<String> eventTypes() {
    return events.stream().map(OutboxEvent::eventType).toList();
  }

  /** Almacén en memoria de la vacante y sus requisitos, con *compare-and-set* por versión. */
  public static final class Store implements JobPostingStorePort, JobRequirementsStorePort {

    public final Map<JobPostingId, JobPosting> postings = new HashMap<>();
    public final Map<JobPostingId, List<JobRequirement>> requirements = new HashMap<>();

    /** Las cuentas por las que se tomó el candado de recuento, en orden. */
    public final List<AccountId> lockedFor = new ArrayList<>();

    @Override
    public Optional<JobPosting> findById(JobPostingId id) {
      return Optional.ofNullable(postings.get(id)).filter(p -> p.status() != JobStatus.DELETED);
    }

    @Override
    public List<JobPosting> findAllByIdsIncludingDeleted(java.util.Collection<JobPostingId> ids) {
      return ids.stream().map(postings::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public void insert(JobPosting posting) {
      postings.put(posting.id(), posting);
    }

    /** Simula que otra edición se adelantó entre leer y escribir. */
    public boolean loseTheRace;

    @Override
    public long lockAndCountPublished(AccountId owner) {
      lockedFor.add(owner);
      return postings.values().stream()
          .filter(p -> p.isOwnedBy(owner) && p.status() == JobStatus.PUBLISHED)
          .count();
    }

    @Override
    public boolean updateIfVersion(JobPosting posting, long expectedVersion) {
      JobPosting stored = postings.get(posting.id());
      if (loseTheRace || stored == null || stored.version() != expectedVersion) {
        return false;
      }
      postings.put(posting.id(), posting);
      return true;
    }

    @Override
    public List<JobRequirement> findByPosting(JobPostingId id) {
      return requirements.getOrDefault(id, List.of());
    }

    @Override
    public void replace(JobPostingId id, List<JobRequirement> items) {
      requirements.put(id, List.copyOf(items));
    }
  }

  /** Las capacidades de cada cuenta, configurables por la prueba. */
  public static final class Capabilities implements CapabilityPort {

    public final Set<AccountId> recruiters = new HashSet<>();
    public final Set<AccountId> candidates = new HashSet<>();

    @Override
    public boolean isRecruiter(AccountId accountId) {
      return recruiters.contains(accountId);
    }

    @Override
    public boolean isCandidate(AccountId accountId) {
      return candidates.contains(accountId);
    }
  }
}
