package com.usac.jobseeker.common.application.audit;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Una acción relevante que queda en el registro de auditoría (`audit_log`, `data-model.md`): quién
 * hizo qué sobre qué. Se conserva con el identificador de quien actuó aunque su cuenta se anonimice
 * (BR-DATA-05), y por eso **nunca** lleva datos personales (BR-DATA-01): `details` admite solo
 * claves y valores cortos y simples, sin texto libre, así que no hay dónde escribir un nombre, un
 * correo ni un contenido.
 *
 * @param action qué pasó, en `MAYUSCULAS_Y_GUION_BAJO` (por ejemplo
 *     `RECRUITER_VERIFICATION_CHANGED`)
 * @param targetType el tipo de lo afectado (por ejemplo `ACCOUNT`)
 * @param targetId el identificador de lo afectado, si lo hay
 * @param details datos de apoyo cortos, como el valor nuevo de un estado
 */
public record AuditEntry(
    AccountId actor, String action, String targetType, UUID targetId, Map<String, String> details) {

  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,59}");
  private static final Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9]{0,29}");
  private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_.:-]{0,80}");

  public AuditEntry {
    Objects.requireNonNull(actor, "actor");
    if (action == null || !CODE.matcher(action).matches()) {
      throw new IllegalArgumentException("la acción debe ser un código en mayúsculas");
    }
    if (targetType == null || !CODE.matcher(targetType).matches()) {
      throw new IllegalArgumentException("el tipo de objetivo debe ser un código en mayúsculas");
    }
    details = Map.copyOf(details == null ? Map.of() : details);
    details.forEach(
        (key, value) -> {
          if (!KEY.matcher(key).matches() || !VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("el detalle solo admite claves y valores simples");
          }
        });
  }
}
