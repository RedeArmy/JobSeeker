package com.usac.jobseeker.auth.application.usecase.handleclavariswebhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.auth.domain.model.IdentityState;
import com.usac.jobseeker.auth.domain.model.IdentityStatus;
import com.usac.jobseeker.common.application.idempotency.ProcessedEventsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Qué hace el caso de uso con cada webhook ya verificado (ADR-0020, superficie C). */
class HandleClavarisWebhookServiceTest {

  private static final Instant T0 = Instant.parse("2026-11-22T12:00:00Z");

  private final Set<String> processed = new HashSet<>();
  private final List<OutboxEvent> published = new ArrayList<>();
  private final InMemoryIdentityState identity = new InMemoryIdentityState();
  private final AccountId account = new AccountId(UUID.randomUUID());
  private boolean publisherFails;
  private HandleClavarisWebhookService service;

  @BeforeEach
  void setUp() {
    ProcessedEventsPort events = (consumer, id) -> processed.add(consumer + ":" + id);
    DomainEventPublisherPort publisher =
        event -> {
          if (publisherFails) {
            throw new IllegalStateException("bandeja caída");
          }
          published.add(event);
        };
    service = new HandleClavarisWebhookService(events, identity, publisher);
    identity.create(account);
  }

  private HandleClavarisWebhookResult handle(String type, UUID eventId, Instant at) {
    return service.execute(new HandleClavarisWebhookCommand(eventId, type, account, at, "trace-1"));
  }

  private IdentityState state() {
    return identity.findIdentityState(account).orElseThrow();
  }

  @Test
  void suspendingMirrorsTheStatusAndRequestsClosingTheSessions() {
    UUID id = UUID.randomUUID();

    assertThat(handle("account.suspended", id, T0)).isEqualTo(HandleClavarisWebhookResult.APPLIED);

    assertThat(state()).isEqualTo(new IdentityState(IdentityStatus.SUSPENDED, T0, id));
    assertThat(published).hasSize(1);
    OutboxEvent out = published.get(0);
    assertThat(out.eventType()).isEqualTo(AccountSessionsInvalidationRequested.EVENT_TYPE);
    assertThat(out.aggregateType()).isEqualTo("account");
    assertThat(out.aggregateId()).isEqualTo(account.value());
    assertThat(out.traceId()).isEqualTo("trace-1");
    assertThat(out.aggregateVersion()).isEqualTo(T0.toEpochMilli());
    assertThat(out.eventVersion()).isOne();
    assertThat(out.id()).isNotEqualTo(id);
    assertThat(out.payload()).contains(account.toString(), "account.suspended", id.toString());
  }

  @Test
  void banningMirrorsSuspendedAndRequestsClosingTheSessionsWithItsOwnReason() {
    UUID id = UUID.randomUUID();

    assertThat(handle("account.banned", id, T0)).isEqualTo(HandleClavarisWebhookResult.APPLIED);

    assertThat(state()).isEqualTo(new IdentityState(IdentityStatus.SUSPENDED, T0, id));
    assertThat(published)
        .singleElement()
        .satisfies(
            out -> {
              assertThat(out.eventType())
                  .isEqualTo(AccountSessionsInvalidationRequested.EVENT_TYPE);
              assertThat(out.payload()).contains("account.banned", id.toString());
            });
  }

  @Test
  void unbanningRestoresTheStatusAndClosesNothing() {
    handle("account.banned", UUID.randomUUID(), T0);
    published.clear();

    handle("account.unbanned", UUID.randomUUID(), T0.plusSeconds(1));

    assertThat(state().status()).isEqualTo(IdentityStatus.ACTIVE);
    assertThat(published).isEmpty();
  }

  @Test
  void aBanArrivingAfterANewerUnbanDoesNotRevertItButStillClosesSessions() {
    handle("account.unbanned", UUID.randomUUID(), T0.plusSeconds(10));
    published.clear();

    handle("account.banned", UUID.randomUUID(), T0);

    assertThat(state().status()).isEqualTo(IdentityStatus.ACTIVE);
    assertThat(published).as("cerrar de más es la dirección segura").hasSize(1);
  }

  @Test
  void aBanThenADeletionLeavesTheIdentityDeletedWhateverComesAfter() {
    handle("account.banned", UUID.randomUUID(), T0);
    handle("account.deleted", UUID.randomUUID(), T0.plusSeconds(1));

    handle("account.unbanned", UUID.randomUUID(), T0.plusSeconds(100));

    assertThat(state().status()).isEqualTo(IdentityStatus.DELETED);
  }

  @Test
  void deletingIsTerminalAndClosesSessions() {
    handle("account.deleted", UUID.randomUUID(), T0);

    assertThat(state().status()).isEqualTo(IdentityStatus.DELETED);
    assertThat(published).hasSize(1);
  }

  @Test
  void aReusedRefreshTokenClosesSessionsWithoutChangingTheStatus() {
    handle("refresh_token.reuse_detected", UUID.randomUUID(), T0);

    assertThat(state()).isEqualTo(IdentityState.initial());
    assertThat(published).hasSize(1);
  }

  @Test
  void reactivatingRestoresTheStatusAndClosesNothing() {
    handle("account.suspended", UUID.randomUUID(), T0);
    published.clear();

    handle("account.reactivated", UUID.randomUUID(), T0.plusSeconds(1));

    assertThat(state().status()).isEqualTo(IdentityStatus.ACTIVE);
    assertThat(published).isEmpty();
  }

  @Test
  void anOldSuspensionArrivingLateDoesNotOverrideTheReactivationButStillClosesSessions() {
    handle("account.reactivated", UUID.randomUUID(), T0.plusSeconds(10));
    published.clear();

    handle("account.suspended", UUID.randomUUID(), T0);

    assertThat(state().status()).isEqualTo(IdentityStatus.ACTIVE);
    assertThat(published).as("cerrar de más es la dirección segura").hasSize(1);
  }

  @Test
  void aReactivationNeverBringsBackADeletedIdentity() {
    handle("account.deleted", UUID.randomUUID(), T0);

    handle("account.reactivated", UUID.randomUUID(), T0.plusSeconds(100));

    assertThat(state().status()).isEqualTo(IdentityStatus.DELETED);
  }

  @Test
  void theSameEventTwiceIsAppliedOnce() {
    UUID id = UUID.randomUUID();

    HandleClavarisWebhookResult first = handle("account.suspended", id, T0);
    HandleClavarisWebhookResult second = handle("account.suspended", id, T0);

    assertThat(first).isEqualTo(HandleClavarisWebhookResult.APPLIED);
    assertThat(second).isEqualTo(HandleClavarisWebhookResult.DUPLICATE);
    assertThat(published).hasSize(1);
  }

  @Test
  void anIrrelevantTypeIsIgnoredAndNotRecordedAsProcessed() {
    assertThat(handle("account.profile_updated", UUID.randomUUID(), T0))
        .isEqualTo(HandleClavarisWebhookResult.IGNORED);

    assertThat(processed).isEmpty();
    assertThat(published).isEmpty();
    assertThat(state()).isEqualTo(IdentityState.initial());
  }

  @Test
  void anAccountThatNeverLoggedInHasNothingToCloseButTheEventIsStillConsumed() {
    HandleClavarisWebhookCommand command =
        new HandleClavarisWebhookCommand(
            UUID.randomUUID(), "account.deleted", new AccountId(UUID.randomUUID()), T0, null);

    assertThat(service.execute(command)).isEqualTo(HandleClavarisWebhookResult.UNKNOWN_ACCOUNT);
    assertThat(published).isEmpty();
  }

  @Test
  void ifPublishingFailsTheFailureReachesTheCallerSoTheTransactionRollsBackAndClavarisRetries() {
    publisherFails = true;

    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> handle("account.suspended", id, T0))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void collaboratorsAreRequired() {
    ProcessedEventsPort events = (consumer, id) -> true;
    DomainEventPublisherPort publisher = event -> {};
    assertThatThrownBy(() -> new HandleClavarisWebhookService(null, identity, publisher))
        .hasMessageContaining("processedEvents");
    assertThatThrownBy(() -> new HandleClavarisWebhookService(events, null, publisher))
        .hasMessageContaining("identity");
    assertThatThrownBy(() -> new HandleClavarisWebhookService(events, identity, null))
        .hasMessageContaining("publisher");
  }

  @Test
  void theCommandRequiresEveryField() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> new HandleClavarisWebhookCommand(null, "t", account, T0, null))
        .hasMessageContaining("eventId");
    assertThatThrownBy(() -> new HandleClavarisWebhookCommand(id, null, account, T0, null))
        .hasMessageContaining("eventType");
    assertThatThrownBy(() -> new HandleClavarisWebhookCommand(id, "t", null, T0, null))
        .hasMessageContaining("accountId");
    assertThatThrownBy(() -> new HandleClavarisWebhookCommand(id, "t", account, null, null))
        .hasMessageContaining("occurredAt");
  }

  @Test
  void theInvalidationPayloadCarriesOnlyIdentifiersAndTheReason() {
    UUID source = UUID.randomUUID();

    String json =
        AccountSessionsInvalidationRequested.payload(account, ClavarisEvent.DELETED, source);

    assertThat(json)
        .isEqualTo(
            "{\"accountId\":\"%s\",\"reason\":\"account.deleted\",\"sourceEventId\":\"%s\"}"
                .formatted(account, source));
  }
}
