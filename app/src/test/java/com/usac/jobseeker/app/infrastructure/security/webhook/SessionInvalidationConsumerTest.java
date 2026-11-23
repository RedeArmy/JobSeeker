package com.usac.jobseeker.app.infrastructure.security.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usac.jobseeker.app.infrastructure.security.liveness.LivenessStore;
import com.usac.jobseeker.app.infrastructure.security.liveness.VaultingAuthorizedClientRepository;
import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.AccountSessionsInvalidationRequested;
import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.ClavarisEvent;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.Session;

class SessionInvalidationConsumerTest {

  @SuppressWarnings("unchecked")
  private final FindByIndexNameSessionRepository<Session> repository =
      mock(FindByIndexNameSessionRepository.class);

  private final LivenessStore liveness = mock(LivenessStore.class);
  private final SessionInvalidationConsumer consumer =
      new SessionInvalidationConsumer(repository, liveness);
  private final UUID account = UUID.randomUUID();

  private OutboxEvent event(String payload) {
    return new OutboxEvent(
        UUID.randomUUID(),
        "account",
        account,
        1,
        AccountSessionsInvalidationRequested.EVENT_TYPE,
        1,
        payload,
        null);
  }

  @Test
  void itOnlyHandlesTheInvalidationEvent() {
    assertThat(consumer.handles(AccountSessionsInvalidationRequested.EVENT_TYPE)).isTrue();
    assertThat(consumer.handles("account.suspended")).isFalse();
    assertThat(consumer.name()).isEqualTo("close-account-sessions");
  }

  @Test
  void itDeletesEverySessionIndexedUnderTheAccount() {
    when(repository.findByPrincipalName(account.toString()))
        .thenReturn(Map.of("s1", new MapSession("s1"), "s2", new MapSession("s2")));

    consumer.consume(
        event(
            AccountSessionsInvalidationRequested.payload(
                new AccountId(account), ClavarisEvent.SUSPENDED, UUID.randomUUID())));

    verify(repository).deleteById("s1");
    verify(repository).deleteById("s2");
  }

  @Test
  void anAccountWithoutSessionsIsNotAnError() {
    when(repository.findByPrincipalName(account.toString())).thenReturn(Map.of());

    consumer.consume(
        event(
            AccountSessionsInvalidationRequested.payload(
                new AccountId(account), ClavarisEvent.DELETED, UUID.randomUUID())));

    verify(repository, never()).deleteById(anyString());
  }

  @Test
  void aPayloadWithoutAnAccountIsDroppedInsteadOfRetriedForever() {
    consumer.consume(event("{}"));
    consumer.consume(event("{\"accountId\":\"  \"}"));

    verify(repository, never()).findByPrincipalName(anyString());
  }

  @Test
  void ifRedisFailsTheErrorPropagatesSoThePollerRetries() {
    when(repository.findByPrincipalName(account.toString()))
        .thenThrow(new IllegalStateException("Redis caído"));

    OutboxEvent request =
        event(
            AccountSessionsInvalidationRequested.payload(
                new AccountId(account), ClavarisEvent.SUSPENDED, UUID.randomUUID()));

    assertThatThrownBy(() -> consumer.consume(request)).isInstanceOf(IllegalStateException.class);
  }

  private static MapSession sessionLinkedTo(String id, String link) {
    MapSession session = new MapSession(id);
    session.setAttribute(VaultingAuthorizedClientRepository.LINK_ATTRIBUTE, link);
    return session;
  }

  private OutboxEvent suspension() {
    return event(
        AccountSessionsInvalidationRequested.payload(
            new AccountId(account), ClavarisEvent.SUSPENDED, UUID.randomUUID()));
  }

  @Test
  void itDeletesTheLivenessRecordOfEverySessionItClosesSoNothingIsLeftOrphan() {
    when(repository.findByPrincipalName(account.toString()))
        .thenReturn(
            Map.of("s1", sessionLinkedTo("s1", "link-1"), "s2", sessionLinkedTo("s2", "link-2")));

    consumer.consume(suspension());

    verify(liveness).delete("link-1");
    verify(liveness).delete("link-2");
    verify(repository).deleteById("s1");
    verify(repository).deleteById("s2");
  }

  @Test
  void aSessionThatNeverLoggedInHasNoLinkAndOnlyTheSessionIsDeleted() {
    when(repository.findByPrincipalName(account.toString()))
        .thenReturn(Map.of("s1", new MapSession("s1")));

    consumer.consume(suspension());

    verify(liveness, never()).delete(any());
    verify(repository).deleteById("s1");
  }

  @Test
  void theLivenessRecordIsDeletedBeforeTheSessionBecauseTheLinkLivesOnlyInTheSession() {
    when(repository.findByPrincipalName(account.toString()))
        .thenReturn(Map.of("s1", sessionLinkedTo("s1", "link-1")));

    consumer.consume(suspension());

    var order = inOrder(liveness, repository);
    order.verify(liveness).delete("link-1");
    order.verify(repository).deleteById("s1");
  }

  @Test
  void ifDeletingTheLivenessRecordFailsTheSessionIsKeptSoTheRetryStillFindsTheLink() {
    when(repository.findByPrincipalName(account.toString()))
        .thenReturn(Map.of("s1", sessionLinkedTo("s1", "link-1")));
    doThrow(new IllegalStateException("Redis caído")).when(liveness).delete("link-1");
    OutboxEvent request = suspension();

    assertThatThrownBy(() -> consumer.consume(request)).isInstanceOf(IllegalStateException.class);

    verify(repository, never()).deleteById(anyString());
  }
}
