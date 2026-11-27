package com.usac.jobseeker.app.infrastructure.idempotency;

import com.usac.jobseeker.common.application.idempotency.IdempotencyKeysPort;
import com.usac.jobseeker.common.domain.error.IdempotencyKeyReusedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * `idempotency_keys` (TD-010, LLD §7). La reserva es un `INSERT … ON CONFLICT DO NOTHING` sobre la
 * clave primaria, no una consulta previa: si otra transacción insertó la misma clave y aún no
 * confirma, Postgres hace esperar a esta hasta que lo haga, y entonces lee su respuesta. Si la otra
 * se revierte, esta inserta y hace el efecto ella.
 */
@Component
public class JdbcIdempotencyKeys implements IdempotencyKeysPort {

  private static final String ACCOUNT = "account";
  private static final String KEY = "key";
  // Una sola fila por clave: la clave primaria es (cuenta, clave).
  private static final int ONE_ROW = 1;

  private final JdbcClient jdbc;

  public JdbcIdempotencyKeys(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<String> claim(AccountId account, String key, String requestHash) {
    int inserted =
        jdbc.sql(
                "insert into idempotency_keys (account_id, key, request_hash)"
                    + " values (:account, :key, :hash) on conflict do nothing")
            .param(ACCOUNT, account.value())
            .param(KEY, key)
            .param("hash", requestHash)
            .update();
    if (inserted == ONE_ROW) {
      return Optional.empty();
    }
    // La respuesta se guarda como una cadena JSON y se lee con #>> '{}': vuelve tal cual se
    // escribió, sin que jsonb la reformatee.
    Stored stored =
        jdbc.sql(
                "select request_hash, response #>> '{}' as response from idempotency_keys"
                    + " where account_id = :account and key = :key")
            .param(ACCOUNT, account.value())
            .param(KEY, key)
            .query((rows, n) -> new Stored(rows.getString(1), rows.getString(2)))
            .optional()
            // Purgada entre la inserción fallida y la lectura: ya no hay nada que repetir.
            .orElse(null);
    if (stored == null) {
      return claim(account, key, requestHash);
    }
    if (!stored.requestHash().equals(requestHash)) {
      throw new IdempotencyKeyReusedException();
    }
    // La otra petición confirmó sin guardar respuesta: nunca debe ocurrir, porque reserva y
    // respuesta van en la misma transacción. Mejor un error que repetir el efecto.
    if (stored.response() == null) {
      throw new IllegalStateException("Clave de idempotencia confirmada sin respuesta");
    }
    return Optional.of(stored.response());
  }

  @Override
  public void complete(AccountId account, String key, String response) {
    int updated =
        jdbc.sql(
                "update idempotency_keys set response = to_jsonb(cast(:response as text))"
                    + " where account_id = :account and key = :key and response is null")
            .param("response", response)
            .param(ACCOUNT, account.value())
            .param(KEY, key)
            .update();
    if (updated != ONE_ROW) {
      throw new IllegalStateException("La clave de idempotencia no estaba reservada");
    }
  }

  /** Borra una tanda de claves anteriores a `cutoff`; devuelve cuántas. */
  public int purgeBefore(Instant cutoff, int limit) {
    return jdbc.sql(
            "delete from idempotency_keys where (account_id, key) in (select account_id, key"
                + " from idempotency_keys where created_at < :cutoff order by created_at"
                + " limit :limit)")
        .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
        .param("limit", limit)
        .update();
  }

  private record Stored(String requestHash, String response) {}
}
