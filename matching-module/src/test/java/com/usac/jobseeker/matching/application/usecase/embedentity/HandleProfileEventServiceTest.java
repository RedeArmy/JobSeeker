package com.usac.jobseeker.matching.application.usecase.embedentity;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Lo que `matching` hace con cada evento de `cv`: encolar con o sin ventana, o borrar ya. */
class HandleProfileEventServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
  private static final Duration WINDOW = Duration.ofSeconds(30);

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final List<String> requests = new ArrayList<>();
  private final List<String> discarded = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private final InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();

  private final HandleProfileEventService service =
      new HandleProfileEventService(
          new EmbeddingWorkQueuePort() {
            public void request(EmbeddingEntity entity, UUID key, Instant notBefore) {
              requests.add(notBefore.toString());
            }

            public List<Job> claim(int limit, Duration lease, int maxAttempts) {
              throw new UnsupportedOperationException();
            }

            public void complete(Job job) {
              throw new UnsupportedOperationException();
            }

            public void retryAt(Job job, Instant notBefore) {
              throw new UnsupportedOperationException();
            }

            public void fail(Job job) {
              throw new UnsupportedOperationException();
            }

            public void discard(EmbeddingEntity entity, UUID key) {
              discarded.add("discard");
            }
          },
          store,
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome),
          Clock.fixed(NOW, ZoneOffset.UTC),
          WINDOW);

  private void handle(ProfileEventKind kind) {
    service.execute(new HandleProfileEventCommand(kind, account));
  }

  @Test
  void anUpdateIsQueuedAfterTheCoalescingWindow() {
    handle(ProfileEventKind.UPDATED);

    assertThat(requests).containsExactly(NOW.plus(WINDOW).toString());
    assertThat(metrics).containsExactly("handle_profile_event:updated");
  }

  @Test
  void aDiscoverabilityChangeIsQueuedWithoutWaitingSoHidingAProfileShowsAtOnce() {
    handle(ProfileEventKind.DISCOVERABILITY_CHANGED);

    assertThat(requests).containsExactly(NOW.toString());
  }

  @Test
  void anAnonymizationBuriesTheAccountAndDiscardsWhatWasPendingWithoutCallingAnyModel() {
    store.registerProfile(account, new CandidateProfileId(UUID.randomUUID()));

    handle(ProfileEventKind.ANONYMIZED);

    assertThat(store.buried).contains(account);
    assertThat(discarded).containsExactly("discard");
    assertThat(requests).isEmpty();
  }

  @Test
  void handlingTheSameEventTwiceIsHarmless() {
    handle(ProfileEventKind.ANONYMIZED);
    handle(ProfileEventKind.ANONYMIZED);

    assertThat(store.buried).containsExactly(account);
  }
}
