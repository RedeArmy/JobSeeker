package com.usac.jobseeker.app.infrastructure.security.webhook;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Firma como lo hace Clavaris (`WebhookSignature.header`, leído de su código): HMAC-SHA256 sobre
 * `"<t>.<cuerpo>"`, un `v1` por secreto. Es independiente del verificador a propósito: si ambos
 * compartieran código, un error común pasaría desapercibido.
 */
final class WebhookSigner {

  private WebhookSigner() {}

  static String header(Instant at, byte[] body, List<String> secrets) {
    StringBuilder header = new StringBuilder("t=").append(at.getEpochSecond());
    for (String secret : secrets) {
      header.append(",v1=").append(hex(at.getEpochSecond(), body, secret));
    }
    return header.toString();
  }

  static String hex(long epoch, byte[] body, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((epoch + ".").getBytes(StandardCharsets.UTF_8));
      mac.update(body);
      return HexFormat.of().formatHex(mac.doFinal());
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
