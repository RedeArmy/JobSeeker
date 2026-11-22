package com.usac.jobseeker.app.infrastructure.security.liveness;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Estado de la prueba de vida y su cerrojo, en Redis (ADR-0020, reglas 3, 5 y 6).
 *
 * <p>El refresh token no vive en el objeto de sesión de Spring: ahí lo copiaría cada petición y,
 * releído «dentro del cerrojo» desde esa copia, un token ya rotado provocaría `reuse_detected`
 * (regla 5). Se guarda aquí, cifrado, bajo un identificador propio y estable (regla 6) que la
 * sesión solo referencia; el identificador de sesión se rota al hacer login y no sirve de clave.
 *
 * <p>Los nombres de clave llevan su propio prefijo: no se mezclan con la caché de scores ni con los
 * buckets de rate limit (dos espacios independientes en la misma instancia, ADR-0011).
 */
public final class LivenessStore {

  private static final String STATE_PREFIX = "jobseeker:liveness:";
  private static final String LOCK_PREFIX = "jobseeker:liveness-lock:";
  private static final String TOKEN = "rt";
  private static final String RENEWED = "renewed";
  private static final String RETRY_AFTER = "retryAfter";
  // Liberar solo si el cerrojo sigue siendo nuestro: si expiró y otro lo tomó, no se le quita.
  private static final DefaultRedisScript<Long> UNLOCK =
      new DefaultRedisScript<>(
          "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) "
              + "else return 0 end",
          Long.class);

  private final StringRedisTemplate redis;
  private final RefreshTokenCipher cipher;
  private final Duration ttl;
  private final SecureRandom random = new SecureRandom();

  /**
   * @param ttl vida máxima del registro; el límite absoluto de la sesión, que es lo más que puede
   *     durar su dueña
   */
  public LivenessStore(StringRedisTemplate redis, RefreshTokenCipher cipher, Duration ttl) {
    this.redis = redis;
    this.cipher = cipher;
    this.ttl = ttl;
  }

  /** Guarda el refresh token recién emitido y devuelve el identificador de enlace de la sesión. */
  public String begin(String refreshToken, Instant now) {
    byte[] raw = new byte[16];
    random.nextBytes(raw);
    String linkId = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    write(linkId, cipher.encrypt(refreshToken, linkId), now, Instant.EPOCH);
    return linkId;
  }

  public Optional<LivenessState> find(String linkId) {
    Map<Object, Object> fields = redis.opsForHash().entries(STATE_PREFIX + linkId);
    if (fields.isEmpty() || fields.get(TOKEN) == null) {
      return Optional.empty();
    }
    return Optional.of(
        new LivenessState(
            (String) fields.get(TOKEN),
            Instant.ofEpochSecond(Long.parseLong((String) fields.get(RENEWED))),
            Instant.ofEpochSecond(Long.parseLong((String) fields.get(RETRY_AFTER)))));
  }

  /** El refresh token en claro; solo se usa para la llamada a Clavaris y nunca se registra. */
  public String refreshTokenOf(String linkId, LivenessState state) {
    return cipher.decrypt(state.encryptedRefreshToken(), linkId);
  }

  /** Clavaris rota el token en cada renovación (un solo uso): se guarda el sucesor. */
  public void recordRenewed(
      String linkId, String newRefreshToken, LivenessState previous, Instant now) {
    String encrypted =
        newRefreshToken == null
            ? previous.encryptedRefreshToken()
            : cipher.encrypt(newRefreshToken, linkId);
    write(linkId, encrypted, now, Instant.EPOCH);
  }

  /** Caché negativa (regla 12): conserva el token y la última renovación buena, y fija la pausa. */
  public void recordTransientFailure(
      String linkId, LivenessState previous, Instant retryNotBefore) {
    write(linkId, previous.encryptedRefreshToken(), previous.lastRenewed(), retryNotBefore);
  }

  public void delete(String linkId) {
    redis.delete(STATE_PREFIX + linkId);
  }

  /**
   * Toma el cerrojo de la sesión esperando hasta {@code wait}; vacío si no lo logró (otra petición
   * está renovando).
   */
  public Optional<String> acquireLock(String linkId, Duration lockTtl, Duration wait) {
    String owner = UUID.randomUUID().toString();
    long deadline = System.nanoTime() + wait.toNanos();
    do {
      if (Boolean.TRUE.equals(
          redis.opsForValue().setIfAbsent(LOCK_PREFIX + linkId, owner, lockTtl))) {
        return Optional.of(owner);
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
    } while (System.nanoTime() < deadline);
    return Optional.empty();
  }

  public void release(String linkId, String owner) {
    redis.execute(UNLOCK, List.of(LOCK_PREFIX + linkId), owner);
  }

  private void write(String linkId, String encryptedToken, Instant renewed, Instant retryAfter) {
    String key = STATE_PREFIX + linkId;
    redis
        .opsForHash()
        .putAll(
            key,
            Map.of(
                TOKEN,
                encryptedToken,
                RENEWED,
                Long.toString(renewed.getEpochSecond()),
                RETRY_AFTER,
                Long.toString(retryAfter.getEpochSecond())));
    redis.expire(key, ttl);
  }
}
