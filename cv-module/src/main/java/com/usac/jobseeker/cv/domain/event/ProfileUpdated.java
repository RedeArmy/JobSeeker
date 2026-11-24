package com.usac.jobseeker.cv.domain.event;

import com.usac.jobseeker.common.domain.model.AccountId;

/**
 * El perfil de una cuenta cambió (`ProfileUpdatedEvent`, domain-model.md §6): `matching` recalcula
 * lo que dependa de él (BR-MATCH-03, BR-ACCT-06).
 *
 * <p>Solo lleva identificadores, el tipo de cambio y la versión: nunca el contenido del perfil
 * (BR-DATA-01, ADR-0016). Quien lo consume lee el dato por la superficie de lectura de `cv`.
 */
public final class ProfileUpdated {

  public static final String AGGREGATE_TYPE = "professional_profile";
  public static final String EVENT_TYPE = "profile.updated";

  /** El tipo de agregado de los cambios del perfil de candidato, que tiene su propia versión. */
  public static final String CANDIDATE_AGGREGATE_TYPE = "candidate_profile";

  /** Cambió el nombre, el titular o la biografía. */
  public static final String CHANGE_IDENTITY = "identity";

  /** Cambió un elemento de una de las colecciones del perfil. */
  public static final String CHANGE_EXPERIENCE = "experience";

  public static final String CHANGE_EDUCATION = "education";
  public static final String CHANGE_CERTIFICATION = "certification";
  public static final String CHANGE_PROJECT = "project";

  /** Cambió el contacto o un dato de elegibilidad del perfil de candidato. */
  public static final String CHANGE_CANDIDATE = "candidate";

  /** Cambió uno de los tres conjuntos del perfil de candidato (reemplazado entero). */
  public static final String CHANGE_SKILLS = "skills";

  public static final String CHANGE_LANGUAGES = "languages";
  public static final String CHANGE_WORK_AUTHORIZATIONS = "work_authorizations";

  private ProfileUpdated() {}

  /** El JSON de la carga; todo son UUID, un número y un valor fijo, sin nada que escapar. */
  public static String payload(AccountId accountId, String change, long version) {
    return "{\"accountId\":\""
        + accountId
        + "\",\"change\":\""
        + change
        + "\",\"version\":"
        + version
        + "}";
  }
}
