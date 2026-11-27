package com.usac.jobseeker.matching.application.usecase.purgeexpiredprofileviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** La purga de retención de los perfiles vistos (TD-035): plazo, tandas y límite por pasada. */
class PurgeExpiredProfileViewsServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-27T03:30:00Z");
  private static final Duration DAY = Duration.ofDays(1);

  private final List<Instant> cutoffs = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private int pending;

  private PurgeExpiredProfileViewsService service(int batchSize, int maxBatches) {
    return new PurgeExpiredProfileViewsService(
        (cutoff, limit) -> {
          cutoffs.add(cutoff);
          int purged = Math.min(limit, pending);
          pending -= purged;
          return purged;
        },
        new ProfileViewRetentionPolicy(Duration.ofDays(90), DAY, batchSize, maxBatches),
        (useCase, outcome) -> metrics.add(outcome),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void itPurgesInBatchesUntilALastPartialOne() {
    pending = 25;

    int purged = service(10, 100).execute();

    assertThat(purged).isEqualTo(25);
    assertThat(cutoffs).hasSize(3).allMatch(NOW.minus(Duration.ofDays(90))::equals);
    assertThat(metrics).containsExactly("purged");
  }

  // Una pasada tiene tope: lo que queda sigue en la siguiente, sin una purga interminable.
  @Test
  void aPassStopsAtItsMaximumNumberOfBatches() {
    pending = 1_000;

    assertThat(service(10, 3).execute()).isEqualTo(30);
    assertThat(pending).isEqualTo(970);
  }

  @Test
  void nothingToPurgeIsCountedAsSuch() {
    assertThat(service(10, 3).execute()).isZero();
    assertThat(metrics).containsExactly("nothing");
  }

  // Borrar una vista que la cuota aún cuenta le devolvería cupo al reclutador.
  @Test
  void theRetentionCannotBeShorterThanTheQuotaWindow() {
    Duration hours = Duration.ofHours(12);

    assertThatThrownBy(() -> new ProfileViewRetentionPolicy(hours, DAY, 10, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProfileViewRetentionPolicy(DAY, DAY, 10, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProfileViewRetentionPolicy(Duration.ofDays(90), DAY, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProfileViewRetentionPolicy(Duration.ofDays(90), DAY, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
