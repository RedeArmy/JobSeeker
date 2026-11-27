package com.usac.jobseeker.app.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * TD-002: las tareas programadas no comparten un solo hilo. El trabajador de embeddings llama a
 * Ollama durante segundos; con el hilo único por defecto retrasaría el sondeo de la bandeja y
 * cualquier lote.
 */
// TD-017: con MockMvc, aunque no lo use, comparte el contexto de las demás pruebas sin emisor.
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
class SchedulerPoolIntegrationTest {

  @Autowired private ThreadPoolTaskScheduler scheduler;

  @Test
  void theSchedulerHasMoreThanOneThread() {
    assertThat(scheduler.getPoolSize()).isGreaterThan(1);
    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
  }

  @Test
  void aSlowTaskDoesNotDelayAnotherOne() throws Exception {
    CountDownLatch slowIsRunning = new CountDownLatch(1);
    CountDownLatch releaseSlow = new CountDownLatch(1);
    CountDownLatch fastRan = new CountDownLatch(1);
    scheduler.schedule(
        () -> {
          slowIsRunning.countDown();
          try {
            releaseSlow.await();
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }
        },
        Instant.now());
    assertThat(slowIsRunning.await(10, TimeUnit.SECONDS)).isTrue();

    scheduler.schedule(fastRan::countDown, Instant.now());

    assertThat(fastRan.await(5, TimeUnit.SECONDS))
        .as("la rápida corre mientras la lenta sigue")
        .isTrue();
    releaseSlow.countDown();
  }
}
