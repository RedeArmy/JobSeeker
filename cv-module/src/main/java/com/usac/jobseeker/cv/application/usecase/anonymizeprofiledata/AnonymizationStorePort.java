package com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Puerto de salida de la anonimización. Cada operación es condicional a que el perfil aún no esté
 * anonimizado y devuelve qué anonimizó, de modo que repetirla no hace nada. Las dos se ejecutan en
 * la transacción del caso de uso: o se anonimiza todo o nada.
 */
public interface AnonymizationStorePort {

  /** Un perfil que esta llamada anonimizó, con la versión nueva que lo dejó en ese estado. */
  record Anonymized(UUID profileId, long version) {}

  /**
   * Anonimiza el perfil profesional: nombre al marcador, titular, biografía y foto a nulo, y borra
   * sus experiencias, formación, certificaciones y proyectos. Vacío si no hay perfil o ya estaba.
   */
  Optional<Anonymized> anonymizeProfessional(
      AccountId accountId, String placeholderName, Instant now);

  /**
   * Anonimiza el perfil de candidato: contacto a nulo, descubribilidad forzada a `false`, y borra
   * sus habilidades, idiomas, autorizaciones de trabajo y su lista de exclusión. Vacío si no hay
   * perfil o ya estaba.
   */
  Optional<Anonymized> anonymizeCandidate(AccountId accountId, Instant now);
}
