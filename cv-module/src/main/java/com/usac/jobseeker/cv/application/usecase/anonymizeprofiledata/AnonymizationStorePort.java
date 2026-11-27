package com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.List;
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
   * Anonimiza el perfil de candidato: contacto y expectativa salarial a nulo, disponibilidad sin
   * declarar, descubribilidad forzada a `false`, y borra sus habilidades, idiomas, autorizaciones
   * de trabajo y su lista de exclusión. Se conservan solo país, modalidad, jornada y reubicación.
   * Vacío si no hay perfil o ya estaba.
   */
  Optional<Anonymized> anonymizeCandidate(AccountId accountId, Instant now);

  /**
   * Marca como borradas las versiones del CV del perfil: sin clave del almacén, sin nombre y sin
   * vigente (BR-DATA-05). Devuelve las claves que tenían, para pedir su borrado físico (ADR-0021
   * §6); la fila se conserva porque una postulación puede referenciarla.
   */
  List<String> discardCvFiles(UUID candidateProfileId, Instant now);

  /**
   * Quita la foto del perfil profesional vivo de la cuenta y devuelve la clave que tenía, para
   * pedir su borrado físico (ADR-0021 §6). Se llama **antes** de anonimizar el perfil, que la deja
   * a nulo.
   */
  Optional<String> discardProfilePhoto(AccountId accountId);
}
