package com.usac.jobseeker.cv.domain.model;

/**
 * El nivel de dominio de un idioma: la escala del Marco Común Europeo más `NATIVE`. El orden de
 * declaración es el del nivel, de menor a mayor, porque las reglas de elegibilidad de idioma
 * comparan «al menos B2» (BR-ELIG-01).
 */
public enum LanguageProficiency {
  A1,
  A2,
  B1,
  B2,
  C1,
  C2,
  NATIVE
}
