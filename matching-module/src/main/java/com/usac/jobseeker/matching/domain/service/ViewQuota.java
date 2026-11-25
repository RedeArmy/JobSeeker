package com.usac.jobseeker.matching.domain.service;

import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * La cuota de perfiles únicos que un reclutador puede ver en la ventana (REQ-R-11): la regla pura,
 * sin base de datos. Lo que cuesta es **cada perfil distinto**, no cada petición ni cada aparición:
 * volver a ver uno que ya vio en la ventana es gratis, así que paginar o refrescar la misma lista
 * no gasta cuota, pero recorrer perfiles nuevos sí.
 *
 * <p>Si lo que queda no alcanza para todos los perfiles nuevos de la página, se sirven los que
 * caben, en el orden del ranking, y el resto se deja fuera. Solo si no cabe **ninguno** y la página
 * tenía candidatos se considera agotada.
 */
public final class ViewQuota {

  /**
   * Qué se puede mostrar y cuánta cuota queda.
   *
   * @param allowed los perfiles que se muestran, en el orden del ranking
   * @param remaining cuántos perfiles **nuevos** más podría ver en la ventana, tras esta página
   * @param exhausted la página tenía candidatos y no se pudo mostrar ninguno
   */
  public record Allowance(List<CandidateProfileId> allowed, int remaining, boolean exhausted) {

    public Allowance {
      allowed = List.copyOf(allowed);
    }
  }

  private ViewQuota() {}

  /**
   * @param ranked los candidatos recuperados, del más al menos cercano
   * @param alreadyViewed los perfiles únicos que ya vio dentro de la ventana
   * @param quota el máximo de perfiles únicos por ventana
   */
  public static Allowance apply(
      List<CandidateProfileId> ranked, Set<CandidateProfileId> alreadyViewed, int quota) {
    if (quota < 0) {
      throw new IllegalArgumentException("la cuota no puede ser negativa");
    }
    int remaining = Math.max(0, quota - alreadyViewed.size());
    List<CandidateProfileId> allowed = new ArrayList<>();
    for (CandidateProfileId candidate : ranked) {
      if (alreadyViewed.contains(candidate)) {
        allowed.add(candidate);
      } else if (remaining > 0) {
        allowed.add(candidate);
        remaining--;
      }
    }
    return new Allowance(allowed, remaining, !ranked.isEmpty() && allowed.isEmpty());
  }
}
