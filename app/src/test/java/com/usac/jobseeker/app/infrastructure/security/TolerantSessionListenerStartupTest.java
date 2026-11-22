package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * El arranque tolerante del oyente de sesiones (ADR-0020, regla 9b): con Redis caído la aplicación
 * arranca igual, reintenta en segundo plano y lo hace visible en métricas. Se prueba con un
 * contenedor simulado, sin Spring ni Redis.
 */
class TolerantSessionListenerStartupTest {

  private static final long RETRY_MILLIS = 50;

  private final MeterRegistry meters = new SimpleMeterRegistry();
  private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
  private TolerantSessionListenerStartup startup =
      new TolerantSessionListenerStartup(RETRY_MILLIS, () -> meters);

  @AfterEach
  void stop() {
    startup.stop();
  }

  private void captureContainer() {
    startup.postProcessBeforeInitialization(
        container, TolerantSessionListenerStartup.SESSION_LISTENER_CONTAINER);
  }

  private double failures() {
    var counter =
        meters.find("jobseeker.redis.listener.start_failed").tag("listener", "session").counter();
    return counter == null ? 0 : counter.count();
  }

  private double subscribedGauge() {
    return meters
        .get("jobseeker.redis.listener.subscribed")
        .tag("listener", "session")
        .gauge()
        .value();
  }

  // ---- captura del contenedor ----

  @Test
  void theSessionListenerContainerIsDeactivatedForAutoStartupAndReturnedUnchanged() {
    Object returned =
        startup.postProcessBeforeInitialization(
            container, TolerantSessionListenerStartup.SESSION_LISTENER_CONTAINER);

    assertThat(returned).isSameAs(container);
    verify(container).setAutoStartup(false);
  }

  @Test
  void anyOtherBeanIsLeftUntouched() {
    Object other = new Object();
    RedisMessageListenerContainer otherContainer = mock(RedisMessageListenerContainer.class);

    assertThat(startup.postProcessBeforeInitialization(other, "otroBean")).isSameAs(other);
    assertThat(startup.postProcessBeforeInitialization(otherContainer, "otroContenedor"))
        .isSameAs(otherContainer);
    verify(otherContainer, never()).setAutoStartup(anyBoolean());
  }

  @Test
  void aBeanWithTheRightNameButAnotherTypeIsLeftUntouched() {
    Object notAContainer = new Object();

    Object returned =
        startup.postProcessBeforeInitialization(
            notAContainer, TolerantSessionListenerStartup.SESSION_LISTENER_CONTAINER);

    assertThat(returned).isSameAs(notAContainer);
    startup.start();
    verify(container, never()).start();
  }

  // ---- arranque ----

  @Test
  void startingStartsTheCapturedContainerOnceAndMarksTheLifecycleAsRunning() {
    captureContainer();
    assertThat(startup.isRunning()).isFalse();

    startup.start();

    assertThat(startup.isRunning()).isTrue();
    verify(container).start();
    assertThat(failures()).isZero();
  }

  @Test
  void anAlreadyRunningContainerIsNotStartedAgain() {
    when(container.isRunning()).thenReturn(true);
    captureContainer();

    startup.start();

    verify(container, never()).start();
  }

  @Test
  void withoutACapturedContainerStartingDoesNothingAndDoesNotFail() {
    startup.start();

    assertThat(startup.isRunning()).isTrue();
    assertThat(startup.isSubscribed()).isFalse();
  }

  @Test
  void stoppingMarksItNotRunningAndNoLongerStartsAnything() {
    captureContainer();
    doThrow(new RedisConnectionFailureException("caído")).when(container).start();
    startup.start();
    startup.stop();

    assertThat(startup.isRunning()).isFalse();
    verify(container, after(300).atMost(1)).start();
  }

  // ---- Redis caído: reintentos y métricas ----

  @Test
  void aFailedStartIsCountedAndRetriedInTheBackgroundUntilItWorks() {
    captureContainer();
    doThrow(new RedisConnectionFailureException("caído"))
        .doThrow(new RedisConnectionFailureException("caído"))
        .doNothing()
        .when(container)
        .start();

    startup.start();

    verify(container, timeout(3000).times(3)).start();
    assertThat(failures()).isEqualTo(2);
  }

  @Test
  void itKeepsRetryingWhileRedisStaysDown() {
    captureContainer();
    doThrow(new RedisConnectionFailureException("caído")).when(container).start();

    startup.start();

    verify(container, timeout(3000).atLeast(4)).start();
    assertThat(failures()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void aStartThatWorksNeverSchedulesARetry() {
    captureContainer();
    doNothing().when(container).start();

    startup.start();

    verify(container, after(400).times(1)).start();
  }

  @Test
  void withoutAMetricsRegistryAFailureIsStillTolerated() {
    startup = new TolerantSessionListenerStartup(RETRY_MILLIS, () -> null);
    captureContainer();
    doThrow(new RedisConnectionFailureException("caído")).doNothing().when(container).start();

    startup.start();

    verify(container, timeout(3000).times(2)).start();
  }

  // ---- el medidor de «sordo o suscrito» (ADR-0012) ----

  @Test
  void theGaugeIsOneOnlyWhenTheContainerRunsAndIsActuallyListening() {
    captureContainer();
    startup.start();

    when(container.isRunning()).thenReturn(true);
    when(container.isListening()).thenReturn(true);
    assertThat(subscribedGauge()).isEqualTo(1);
  }

  @Test
  void aContainerThatRunsButDoesNotListenIsReportedAsDeaf() {
    captureContainer();
    startup.start();

    // Con Redis inalcanzable el contenedor «corre» pero no escucha.
    when(container.isRunning()).thenReturn(true);
    when(container.isListening()).thenReturn(false);

    assertThat(subscribedGauge()).isZero();
    assertThat(startup.isSubscribed()).isFalse();
  }

  @Test
  void aContainerThatIsNotRunningIsNotSubscribedEvenIfItReportsListening() {
    captureContainer();
    when(container.isRunning()).thenReturn(false);
    when(container.isListening()).thenReturn(true);

    assertThat(startup.isSubscribed()).isFalse();
  }

  @Test
  void theGaugeIsRegisteredOnlyOnceNoMatterHowManyAttempts() {
    captureContainer();
    doThrow(new RedisConnectionFailureException("caído")).when(container).start();

    startup.start();
    verify(container, timeout(3000).atLeast(3)).start();

    assertThat(meters.find("jobseeker.redis.listener.subscribed").gauges()).hasSize(1);
  }

  @Test
  void withoutAMetricsRegistryNoGaugeIsRegisteredAndNothingFails() {
    startup = new TolerantSessionListenerStartup(RETRY_MILLIS, () -> null);
    captureContainer();

    startup.start();

    verify(container, times(1)).start();
  }
}
