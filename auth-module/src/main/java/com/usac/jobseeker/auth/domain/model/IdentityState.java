package com.usac.jobseeker.auth.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * El estado de la identidad y el último evento que lo fijó. Decide qué evento de Clavaris se aplica
 * cuando llegan desordenados, repetidos o empatados (ADR-0020, superficie C):
 *
 * <ul>
 *   <li>El orden es la tupla `(occurredAt, event_id)`, no solo `occurredAt`: con un empate exacto
 *       entre estados opuestos el resultado dependía del orden de llegada (7 de 30 cuentas).
 *   <li>`DELETED` es terminal y gana siempre, llegue cuando llegue y con la marca que traiga.
 *   <li>Un evento no más reciente que el aplicado no cambia nada: «gana el último en llegar» deja
 *       el espejo en un estado incorrecto.
 * </ul>
 *
 * <p>Las marcas se comparan con precisión de microsegundos: es la de Postgres, y el adaptador SQL y
 * el dominio deben decidir igual.
 *
 * @param eventAt marca del último evento aplicado; nula si aún no hubo ninguno
 * @param eventId su identificador, que desempata; nulo si aún no hubo ninguno
 */
public record IdentityState(IdentityStatus status, Instant eventAt, UUID eventId) {

  public IdentityState {
    Objects.requireNonNull(status, "status");
    if ((eventAt == null) != (eventId == null)) {
      throw new IllegalArgumentException("la marca y el identificador del evento van juntos");
    }
    if (eventAt != null) {
      eventAt = eventAt.truncatedTo(ChronoUnit.MICROS);
    }
  }

  /** El estado de una cuenta sobre la que aún no se aplicó ningún evento. */
  public static IdentityState initial() {
    return new IdentityState(IdentityStatus.ACTIVE, null, null);
  }

  /**
   * Aplica un evento y devuelve el estado nuevo, o vacío si no cambia nada.
   *
   * @return vacío cuando la identidad ya estaba borrada o el evento no es más reciente
   */
  public Optional<IdentityState> accept(IdentityStatus incoming, Instant at, UUID id) {
    Objects.requireNonNull(incoming, "incoming");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(id, "id");
    if (status == IdentityStatus.DELETED) {
      return Optional.empty();
    }
    if (incoming == IdentityStatus.DELETED || supersedes(at.truncatedTo(ChronoUnit.MICROS), id)) {
      return Optional.of(new IdentityState(incoming, at, id));
    }
    return Optional.empty();
  }

  private boolean supersedes(Instant at, UUID id) {
    if (eventAt == null) {
      return true;
    }
    int byTime = at.compareTo(eventAt);
    return byTime > 0 || (byTime == 0 && compareUnsigned(id, eventId) > 0);
  }

  /**
   * Compara dos UUID byte a byte sin signo, que es como los ordena Postgres. `UUID.compareTo` los
   * compara con signo y daría otro orden: un empate se resolvería distinto en el dominio y en SQL.
   */
  public static int compareUnsigned(UUID left, UUID right) {
    int byMostSignificant =
        Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
    return byMostSignificant == 0
        ? Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits())
        : byMostSignificant;
  }
}
