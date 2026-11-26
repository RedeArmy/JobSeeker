package com.usac.jobseeker.matching.infrastructure.adapter.in.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.matching.application.usecase.migratealgorithmversion.MigrateAlgorithmVersionUseCase;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * El mantenimiento trabaja por lotes, cede entre pasadas y una pasada fallida no cancela las
 * siguientes.
 */
class ScoreMaintenanceWorkerTest {

  private final Deque<Integer> batches = new ArrayDeque<>();
  private final List<String> calls = new ArrayList<>();
  private RuntimeException failure;

  private final ScoreMaintenanceWorker worker =
      new ScoreMaintenanceWorker(
          new MigrateAlgorithmVersionUseCase() {
            public int execute() {
              calls.add("execute");
              if (failure != null) {
                throw failure;
              }
              return batches.isEmpty() ? 0 : batches.poll();
            }

            public int purgeExpiredSnapshots() {
              calls.add("purge");
              return 0;
            }
          });

  @Test
  void itMigratesBatchesUntilThereIsNothingLeftAndThenPurges() {
    batches.addAll(List.of(5, 5, 2));

    worker.run();

    assertThat(calls).containsExactly("execute", "execute", "execute", "execute", "purge");
  }

  @Test
  void withNothingToMigrateItOnlyPurges() {
    worker.run();

    assertThat(calls).containsExactly("execute", "purge");
  }

  // Una pasada no puede retener al hilo para siempre aunque siempre haya algo por migrar.
  @Test
  void aPassTakesAtMostABoundedNumberOfBatches() {
    for (int i = 0; i < 100; i++) {
      batches.add(1);
    }

    worker.run();

    assertThat(calls.stream().filter("execute"::equals).count()).isEqualTo(20);
    assertThat(calls).endsWith("purge");
  }

  @Test
  void aFailureIsContainedSoTheNextScheduledPassStillRuns() {
    failure = new IllegalStateException("base caída");

    worker.run();
    failure = null;
    worker.run();

    assertThat(calls).containsExactly("execute", "execute", "purge");
  }
}
