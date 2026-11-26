package com.usac.jobseeker.matching.domain.service;

import com.usac.jobseeker.matching.domain.model.Availability;
import java.util.Comparator;
import java.util.UUID;

/**
 * El orden final de los sugeridos (etapa 3 del ADR-0017). Se ordena por puntaje y `OPEN_TO_WORK`
 * funciona como **desempate con tolerancia** (BR-MATCH-07, ADR-0030): adelanta a quien le saca
 * menos de `tolerance` puntos (a diferencia exacta gana el de mayor puntaje). Con 1 (por defecto)
 * es la resolución del porcentaje mostrado, de modo que dos candidatos que el reclutador ve con el
 * mismo porcentaje (que difieren menos de un punto) se ordenan por disponibilidad; un 40 %
 * `OPEN_TO_WORK` nunca supera a un 90 %. El desempate **nunca modifica el puntaje**.
 *
 * <p>La tolerancia es continua y no una banda: con bandas fijas dos puntajes a una décima de
 * distancia podrían quedar a uno y otro lado de un corte y la prioridad aparecería o desaparecería
 * sin motivo. Con una tolerancia el orden es transitivo y sin saltos; con 0 no hay prioridad.
 *
 * <p>La disponibilidad que llega ya es la que *ese* reclutador puede ver, `UNSET` si el candidato
 * lo excluyó (BR-MATCH-09), así que un excluido no sube posiciones ni delata su estado por el
 * orden. Se desempata en último lugar por el identificador para que el orden sea determinista.
 */
public final class SuggestionRanking {

  /** Lo que el orden necesita saber de un candidato. */
  public record Entry(UUID candidate, double overall, Availability visibleAvailability) {}

  /** La tolerancia más ancha que se admite: más allá la prioridad dejaría de ser un desempate. */
  public static final double MAX_TOLERANCE = 10.0;

  private SuggestionRanking() {}

  public static Comparator<Entry> order(double tolerance) {
    if (!(tolerance >= 0 && tolerance <= MAX_TOLERANCE)) {
      throw new IllegalArgumentException("la tolerancia debe estar entre 0 y " + MAX_TOLERANCE);
    }
    return Comparator.comparingDouble((Entry e) -> orderingKey(e, tolerance))
        .reversed()
        .thenComparing(Comparator.comparingDouble(Entry::overall).reversed())
        .thenComparing(Entry::candidate);
  }

  // La clave solo ordena: el puntaje que se muestra y se guarda es `overall`, intacto.
  private static double orderingKey(Entry entry, double tolerance) {
    return entry.visibleAvailability() == Availability.OPEN_TO_WORK
        ? entry.overall() + tolerance
        : entry.overall();
  }
}
