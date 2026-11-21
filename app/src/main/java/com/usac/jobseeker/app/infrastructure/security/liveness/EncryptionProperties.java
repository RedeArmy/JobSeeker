package com.usac.jobseeker.app.infrastructure.security.liveness;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Claves con las que se cifra en reposo el refresh token (ADR-0020, regla 3).
 *
 * @param activeKid identificador de la clave con la que se cifra
 * @param keys lista `kid:clave` separada por comas, con la clave en base64 de 32 bytes (AES-256);
 *     rotar es añadir una clave nueva y activarla, las anteriores solo descifran
 */
@ConfigurationProperties("jobseeker.auth.encryption")
public record EncryptionProperties(String activeKid, String keys) {

  /** `kid` → clave en base64, en el orden declarado. Vacío si no hay claves configuradas. */
  public Map<String, String> parsedKeys() {
    Map<String, String> parsed = new LinkedHashMap<>();
    if (keys == null || keys.isBlank()) {
      return parsed;
    }
    Arrays.stream(keys.split(","))
        .map(String::trim)
        .filter(entry -> !entry.isEmpty())
        .forEach(
            entry -> {
              int separator = entry.indexOf(':');
              if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEYS debe tener el formato kid:clave separado por comas");
              }
              parsed.put(entry.substring(0, separator), entry.substring(separator + 1));
            });
    return parsed;
  }
}
