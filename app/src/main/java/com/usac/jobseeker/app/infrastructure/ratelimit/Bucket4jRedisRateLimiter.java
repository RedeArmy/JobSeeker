package com.usac.jobseeker.app.infrastructure.ratelimit;

import com.usac.jobseeker.app.infrastructure.ratelimit.RateLimitProperties.Policy;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cubo de fichas distribuido en Redis (ADR-0011). La clave es `ratelimit:{política}:{cuenta|ip}`,
 * en un espacio de nombres separado del de las puntuaciones en caché.
 *
 * <p>Cada cubo se borra solo cuando vuelve a estar lleno y pasa el margen de expiración: no se
 * acumulan claves de IP que no volvieron.
 */
class Bucket4jRedisRateLimiter implements RateLimiter {

  private static final Logger LOG = LoggerFactory.getLogger(Bucket4jRedisRateLimiter.class);
  private static final String KEY_PREFIX = "ratelimit:";
  private static final Duration LOG_INTERVAL = Duration.ofSeconds(30);

  /** Tras un fallo de conexión no se reintenta de inmediato: cada intento cuesta su espera. */
  private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(5);

  private final Supplier<StatefulRedisConnection<byte[], byte[]>> connector;
  private final MeterRegistry meters;
  private final LongSupplier nanoClock;
  // AtomicReference y no `volatile`: la lectura de la ruta caliente no toma ningún monitor.
  private final AtomicReference<LettuceBasedProxyManager<byte[]>> buckets = new AtomicReference<>();
  private long nextConnectionAttempt;
  private long lastUnavailableLog;
  private boolean unavailableLogged;

  /**
   * La conexión se abre en el primer uso y no al construir: la aplicación debe arrancar con Redis
   * caído (ADR-0020, regla 9) y el límite de tasa no puede ser lo que lo impida. El reloj es
   * inyectable para poder probar el retroceso y el espaciado de los avisos sin esperar.
   */
  Bucket4jRedisRateLimiter(
      Supplier<StatefulRedisConnection<byte[], byte[]>> connector,
      MeterRegistry meters,
      LongSupplier nanoClock) {
    this.connector = connector;
    this.meters = meters;
    this.nanoClock = nanoClock;
  }

  /** Conexión real a Redis con codificación de bytes, que es lo que Bucket4j necesita. */
  static Bucket4jRedisRateLimiter over(RedisClient client, MeterRegistry meters) {
    return new Bucket4jRedisRateLimiter(
        () -> client.connect(RedisCodec.of(ByteArrayCodec.INSTANCE, ByteArrayCodec.INSTANCE)),
        meters,
        System::nanoTime);
  }

  // Ruta caliente: se llama una o dos veces por petición, así que una vez conectado no debe tomar
  // ningún monitor (con hilos virtuales, además, un `synchronized` fijaría el hilo portador).
  private LettuceBasedProxyManager<byte[]> connectedBuckets() {
    LettuceBasedProxyManager<byte[]> ready = buckets.get();
    return ready != null ? ready : connect();
  }

  // Ruta fría: solo el primer uso y los reintentos tras un fallo. Registra el retroceso ante
  // cualquier fallo de conexión y relanza: el llamador decide qué hacer.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private synchronized LettuceBasedProxyManager<byte[]> connect() {
    LettuceBasedProxyManager<byte[]> ready = buckets.get();
    if (ready != null) {
      return ready;
    }
    long now = nanoClock.getAsLong();
    if (now - nextConnectionAttempt < 0) {
      throw new IllegalStateException("Redis sin responder; se reintenta en unos segundos");
    }
    try {
      ready =
          LettuceBasedProxyManager.builderFor(connector.get())
              .withExpirationStrategy(
                  ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                      Duration.ofMinutes(1)))
              .build();
      buckets.set(ready);
      return ready;
    } catch (RuntimeException failure) {
      nextConnectionAttempt = now + RECONNECT_BACKOFF.toNanos();
      throw failure;
    }
  }

  // Cualquier fallo del almacén (conexión, tiempo de espera, protocolo) deja pasar la petición: ver
  // RateLimiter. Es la decisión inversa a la de un control de acceso, y queda medida y registrada.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  @Override
  public RateLimitDecision tryConsume(String policyName, Policy policy, String key) {
    try {
      byte[] redisKey = (KEY_PREFIX + policyName + ":" + key).getBytes(StandardCharsets.UTF_8);
      Supplier<BucketConfiguration> configuration = () -> configuration(policy);
      ConsumptionProbe probe =
          connectedBuckets()
              .builder()
              .build(redisKey, configuration)
              .tryConsumeAndReturnRemaining(1);
      RateLimitDecision decision =
          new RateLimitDecision(
              probe.isConsumed(),
              policy.capacity(),
              probe.getRemainingTokens(),
              probe.isConsumed() ? 0 : retryAfter(probe));
      count(policyName, decision.allowed() ? "allowed" : "limited");
      return decision;
    } catch (RuntimeException unavailable) {
      count(policyName, "unavailable");
      logUnavailable(unavailable);
      return RateLimitDecision.unavailable(policy.capacity());
    }
  }

  private static BucketConfiguration configuration(Policy policy) {
    return BucketConfiguration.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(policy.capacity())
                .refillGreedy(policy.refillTokens(), policy.refillPeriod())
                .build())
        .build();
  }

  /** Segundos hacia arriba: un `Retry-After: 0` invitaría a reintentar de inmediato y fallar. */
  private static long retryAfter(ConsumptionProbe probe) {
    return Math.max(
        1, Duration.ofNanos(probe.getNanosToWaitForRefill()).plusMillis(999).toSeconds());
  }

  private void count(String policy, String outcome) {
    meters.counter("jobseeker_ratelimit_total", "policy", policy, "outcome", outcome).increment();
  }

  /** A lo sumo un aviso cada 30 s: con Redis caído habría uno por petición. */
  private synchronized void logUnavailable(RuntimeException cause) {
    long now = nanoClock.getAsLong();
    if (unavailableLogged && now - lastUnavailableLog < LOG_INTERVAL.toNanos()) {
      return;
    }
    unavailableLogged = true;
    lastUnavailableLog = now;
    LOG.warn(
        "event=ratelimit_unavailable El almacén del límite de tasa no responde; se deja pasar",
        cause);
  }
}
