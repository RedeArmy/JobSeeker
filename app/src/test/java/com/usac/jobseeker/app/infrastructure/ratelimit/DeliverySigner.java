package com.usac.jobseeker.app.infrastructure.ratelimit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Firma una entrega como Clavaris: HMAC-SHA256 sobre `"<t>.<cuerpo>"`. */
final class DeliverySigner {

  private DeliverySigner() {}

  static String header(byte[] body, String secret) {
    long epoch = Instant.now().getEpochSecond();
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((epoch + ".").getBytes(StandardCharsets.UTF_8));
      mac.update(body);
      return "t=" + epoch + ",v1=" + HexFormat.of().formatHex(mac.doFinal());
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
