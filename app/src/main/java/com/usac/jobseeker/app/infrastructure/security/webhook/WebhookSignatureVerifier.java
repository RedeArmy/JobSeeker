package com.usac.jobseeker.app.infrastructure.security.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifica `Clavaris-Signature: t=<epoch>,v1=<hex>[,v1=<hex>…]` (ADR-0020, superficie C).
 *
 * <p>La firma es HMAC-SHA256 con el secreto sobre `"<t>.<cuerpo crudo>"`, así que se calcula sobre
 * los bytes exactos recibidos: reserializar el JSON cambiaría el contenido y la firma dejaría de
 * casar. La comparación es de tiempo constante y se prueba con todos los `v1` y todos los secretos
 * para no revelar por tiempo cuál casó, y para aceptar la rotación (secreto nuevo y anterior).
 */
public final class WebhookSignatureVerifier {

  /** Resultado de la verificación; solo `VALID` autentica. */
  public enum Outcome {
    VALID,
    /** Cabecera ausente o con otro formato. */
    MALFORMED,
    /** La marca de tiempo queda fuera de la tolerancia (repetición de una entrega vieja). */
    STALE,
    /** Ninguna firma casa con ningún secreto. */
    MISMATCH
  }

  private static final String ALGORITHM = "HmacSHA256";
  private static final String TIMESTAMP_PREFIX = "t=";
  private static final String SIGNATURE_PREFIX = "v1=";
  private static final int SHA256_HEX_LENGTH = 64;

  /** Una firma ilegible: vacía, para no devolver nulo. Nunca coincide con un HMAC real. */
  private static final byte[] NO_SIGNATURE = new byte[0];

  private final List<String> secrets;
  private final Duration tolerance;

  public WebhookSignatureVerifier(List<String> secrets, Duration tolerance) {
    this.secrets = List.copyOf(secrets);
    this.tolerance = tolerance;
  }

  public Outcome verify(String header, byte[] rawBody, Instant now) {
    if (header == null) {
      return Outcome.MALFORMED;
    }
    Long timestamp = null;
    boolean timestampSeen = false;
    List<byte[]> signatures = new ArrayList<>();
    for (String part : header.split(",", -1)) {
      String item = part.strip();
      if (item.startsWith(TIMESTAMP_PREFIX)) {
        // Solo cuenta el primer `t=`: uno posterior no puede reemplazar al firmado. Si el primero
        // está mal formado `timestamp` queda nulo y la cabecera entera se rechaza más abajo.
        if (!timestampSeen) {
          timestampSeen = true;
          timestamp = parseEpoch(item.substring(TIMESTAMP_PREFIX.length()));
        }
      } else if (item.startsWith(SIGNATURE_PREFIX)) {
        byte[] decoded = decodeHex(item.substring(SIGNATURE_PREFIX.length()));
        if (decoded.length > 0) {
          signatures.add(decoded);
        }
      }
    }
    if (timestamp == null || signatures.isEmpty()) {
      return Outcome.MALFORMED;
    }
    // La firma se comprueba antes que la vigencia: así quien no conoce el secreto no aprende nada
    // sobre la ventana de tiempo ni distingue un cuerpo válido de uno inválido.
    if (!anySignatureMatches(timestamp, rawBody, signatures)) {
      return Outcome.MISMATCH;
    }
    long skew = Math.abs(now.getEpochSecond() - timestamp);
    return skew > tolerance.toSeconds() ? Outcome.STALE : Outcome.VALID;
  }

  private boolean anySignatureMatches(long timestamp, byte[] rawBody, List<byte[]> signatures) {
    byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
    boolean matched = false;
    for (String secret : secrets) {
      byte[] expected = hmac(secret, prefix, rawBody);
      for (byte[] candidate : signatures) {
        // Sin cortocircuito: se recorren todas para que el tiempo no dependa de cuál casó.
        matched |= MessageDigest.isEqual(expected, candidate);
      }
    }
    return matched;
  }

  private static byte[] hmac(String secret, byte[] prefix, byte[] body) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      mac.update(prefix);
      mac.update(body);
      return mac.doFinal();
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      // HmacSHA256 lo garantiza toda JVM: un fallo aquí es un error de programación, no de entrada.
      throw new IllegalStateException("No se pudo calcular el HMAC del webhook", e);
    }
  }

  private static Long parseEpoch(String text) {
    if (text.isEmpty() || text.length() > 12 || !text.chars().allMatch(Character::isDigit)) {
      return null;
    }
    return Long.parseLong(text);
  }

  private static byte[] decodeHex(String text) {
    if (text.length() != SHA256_HEX_LENGTH) {
      return NO_SIGNATURE;
    }
    try {
      return HexFormat.of().parseHex(text);
    } catch (IllegalArgumentException _) {
      return NO_SIGNATURE;
    }
  }
}
