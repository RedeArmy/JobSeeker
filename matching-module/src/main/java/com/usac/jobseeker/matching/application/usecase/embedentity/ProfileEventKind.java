package com.usac.jobseeker.matching.application.usecase.embedentity;

/** Los eventos de `cv` que cambian el embedding de un perfil o su visibilidad. */
public enum ProfileEventKind {
  /** El perfil profesional o el de candidato cambió: hay que recalcular, agrupando ediciones. */
  UPDATED,
  /** La descubribilidad cambió: debe notarse enseguida. */
  DISCOVERABILITY_CHANGED,
  /** Los datos de la cuenta se anonimizaron (BR-DATA-05): se borra el vector ya. */
  ANONYMIZED
}
