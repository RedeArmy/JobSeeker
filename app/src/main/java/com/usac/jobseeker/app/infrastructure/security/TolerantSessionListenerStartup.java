package com.usac.jobseeker.app.infrastructure.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Arranque tolerante del oyente de sesiones de Spring Session (ADR-0020, regla 9b). El contenedor
 * que oye las sesiones expiradas se suscribe al iniciar el contexto y, con Redis inalcanzable,
 * hacía fallar todo el proceso. La aplicación debe arrancar y seguir sirviendo sin Redis, así que
 * este contenedor no lo inicia el contexto: lo inicia esta clase y, mientras no puede, reintenta en
 * segundo plano. Hasta que lo logra no se oyen los eventos de expiración y la limpieza periódica de
 * Spring Session retira los restos. Mismo patrón que `TolerantSessionListenerStartup` de
 * ../Clavaris.
 */
final class TolerantSessionListenerStartup implements BeanPostProcessor, SmartLifecycle {

  static final String SESSION_LISTENER_CONTAINER = "springSessionRedisMessageListenerContainer";
  private static final Logger LOG = LoggerFactory.getLogger(TolerantSessionListenerStartup.class);
  private static final long DEFAULT_RETRY_MILLIS = 10_000;

  private final long retryMillis;
  private final Supplier<MeterRegistry> meters;
  private final AtomicBoolean gaugeRegistered = new AtomicBoolean();
  private final AtomicReference<RedisMessageListenerContainer> container = new AtomicReference<>();
  private final ScheduledExecutorService retries =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "session-listener-start");
            thread.setDaemon(true);
            return thread;
          });
  private volatile boolean running;

  TolerantSessionListenerStartup(Supplier<MeterRegistry> meters) {
    this(DEFAULT_RETRY_MILLIS, meters);
  }

  TolerantSessionListenerStartup(long retryMillis, Supplier<MeterRegistry> meters) {
    this.retryMillis = retryMillis;
    this.meters = meters;
  }

  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName) {
    if (SESSION_LISTENER_CONTAINER.equals(beanName)
        && bean instanceof RedisMessageListenerContainer listener) {
      listener.setAutoStartup(false);
      container.set(listener);
    }
    return bean;
  }

  @Override
  public void start() {
    running = true;
    attempt();
  }

  // PMD.AvoidCatchingGenericException: el tipo de fallo depende del controlador y de en qué punto
  // de
  // la suscripción ocurre; todos significan «reintentar más tarde», nunca «detener el proceso».
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void attempt() {
    registerGaugeOnce();
    RedisMessageListenerContainer target = container.get();
    if (!running || target == null || target.isRunning()) {
      return;
    }
    try {
      target.start();
    } catch (RuntimeException failure) {
      countFailure();
      LOG.warn(
          "event=session_listener_start_failed reason={} retryInMillis={}",
          failure.getClass().getSimpleName(),
          retryMillis);
      retries.schedule(this::attempt, retryMillis, TimeUnit.MILLISECONDS);
    }
  }

  // Visibilidad (ADR-0012): si el oyente está suscrito (1) o sordo (0), para una gráfica y una
  // alerta.
  private void registerGaugeOnce() {
    MeterRegistry registry = meters.get();
    if (registry != null && gaugeRegistered.compareAndSet(false, true)) {
      registry.gauge(
          "jobseeker.redis.listener.subscribed",
          List.of(Tag.of("listener", "session")),
          this,
          startup -> startup.isSubscribed() ? 1 : 0);
    }
  }

  // `isRunning()` solo dice que el contenedor arrancó; `isListening()` que hay una suscripción
  // activa
  // a Redis. Con Redis inalcanzable el contenedor «corre» pero no escucha: el oyente está sordo.
  boolean isSubscribed() {
    RedisMessageListenerContainer current = container.get();
    return current != null && current.isRunning() && current.isListening();
  }

  private void countFailure() {
    MeterRegistry registry = meters.get();
    if (registry != null) {
      registry.counter("jobseeker.redis.listener.start_failed", "listener", "session").increment();
    }
  }

  @Override
  public void stop() {
    running = false;
    retries.shutdownNow();
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
