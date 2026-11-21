package com.usac.jobseeker.app.infrastructure.security.liveness;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifra el refresh token en reposo con AES-256-GCM (ADR-0020, regla 3). El valor guardado es
 * `kid.base64url(iv ‖ texto cifrado)`: el `kid` permite rotar la clave sin invalidar sesiones.
 *
 * <p>Los datos asociados autenticados (AAD) atan el valor a su sesión: un valor copiado a otro
 * registro de Redis no descifra. Es defensa en profundidad; la protección principal es que el token
 * vive solo en el servidor y se revoca al cerrar sesión.
 */
public final class RefreshTokenCipher {

  private static final int KEY_BYTES = 32;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

  private final Map<String, SecretKey> keys;
  private final String activeKid;
  private final SecureRandom random = new SecureRandom();

  private RefreshTokenCipher(Map<String, SecretKey> keys, String activeKid) {
    this.keys = keys;
    this.activeKid = activeKid;
  }

  /** Construye el cifrador; falla con un mensaje claro si la configuración está mal formada. */
  public static RefreshTokenCipher from(EncryptionProperties properties) {
    Map<String, SecretKey> keys = new HashMap<>();
    properties
        .parsedKeys()
        .forEach(
            (kid, base64) -> {
              if (!KID.matcher(kid).matches()) {
                throw new IllegalStateException(
                    "el kid de TOKEN_ENCRYPTION_KEYS solo admite letras, números, _ y -");
              }
              byte[] raw;
              try {
                raw = Base64.getDecoder().decode(base64);
              } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                    "la clave '" + kid + "' de TOKEN_ENCRYPTION_KEYS no es base64", invalid);
              }
              if (raw.length != KEY_BYTES) {
                throw new IllegalStateException(
                    "la clave '" + kid + "' debe tener 32 bytes (AES-256); tiene " + raw.length);
              }
              keys.put(kid, new SecretKeySpec(raw, "AES"));
            });
    String active = properties.activeKid();
    if (!keys.isEmpty() && (active == null || !keys.containsKey(active))) {
      throw new IllegalStateException(
          "TOKEN_ENCRYPTION_ACTIVE_KID debe nombrar una de las claves de TOKEN_ENCRYPTION_KEYS");
    }
    return new RefreshTokenCipher(Map.copyOf(keys), keys.isEmpty() ? null : active);
  }

  /** `false` si no hay ninguna clave: el cifrado no puede usarse y el login debe rechazarse. */
  public boolean available() {
    return activeKid != null;
  }

  public String encrypt(String plain, String associatedData) {
    if (!available()) {
      throw new IllegalStateException("no hay clave de cifrado configurada");
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKid), new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
      byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      ByteBuffer packed = ByteBuffer.allocate(iv.length + encrypted.length);
      packed.put(iv).put(encrypted);
      return activeKid
          + "."
          + Base64.getUrlEncoder().withoutPadding().encodeToString(packed.array());
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("no se pudo cifrar el token", failure);
    }
  }

  /**
   * @throws UndecipherableTokenException clave retirada, valor alterado o AAD distinta
   */
  public String decrypt(String stored, String associatedData) {
    int separator = stored.indexOf('.');
    if (separator <= 0) {
      throw new UndecipherableTokenException("formato desconocido");
    }
    SecretKey key = keys.get(stored.substring(0, separator));
    if (key == null) {
      throw new UndecipherableTokenException("clave retirada");
    }
    try {
      byte[] packed = Base64.getUrlDecoder().decode(stored.substring(separator + 1));
      if (packed.length <= IV_BYTES) {
        throw new UndecipherableTokenException("valor truncado");
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
      return new String(
          cipher.doFinal(packed, IV_BYTES, packed.length - IV_BYTES), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException failure) {
      throw new UndecipherableTokenException("valor alterado o clave incorrecta");
    }
  }

  /** El valor guardado no puede descifrarse: la sesión se cierra, no se reintenta. */
  public static final class UndecipherableTokenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UndecipherableTokenException(String reason) {
      super(reason);
    }
  }
}
